package com.databricks.labs.tika

import org.apache.tika.exception.TikaException
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.{Metadata, TikaCoreProperties}
import org.apache.tika.parser.microsoft.OfficeParserConfig
import org.apache.tika.parser.ocr.TesseractOCRConfig
import org.apache.tika.parser.pdf.PDFParserConfig
import org.apache.tika.parser.{AutoDetectParser, ParseContext, Parser}
import org.apache.tika.sax.{BodyContentHandler, ToXMLContentHandler, WriteOutContentHandler}
import org.apache.poi.util.IOUtils
import org.apache.poi.openxml4j.opc.ZipPackage
import org.apache.poi.openxml4j.util.{ZipInputStreamZipEntrySource, ZipSecureFile}

import java.io.IOException
import scala.xml.SAXException

object TikaExtractor {

  @throws[IOException]
  @throws[SAXException]
  @throws[TikaException]
  def extract(stream: TikaInputStream, filename: String, writeLimit: Int = -1, timeout: Int = 120, byteArrayMaxOverride: Int = 300000000, enableXMLOutput: Boolean = true): TikaContent = {

    // Configure each parser if required
    val pdfConfig = new PDFParserConfig
    val officeConfig = new OfficeParserConfig
    val tesseractConfig = new TesseractOCRConfig
    tesseractConfig.setTimeoutSeconds(timeout) // Set the Tesseract OCR
    val parseContext = new ParseContext

    val handler = if (enableXMLOutput) {
      val xmlHandler = new ToXMLContentHandler()
      new WriteOutContentHandler(xmlHandler, writeLimit)
    } else {
      new BodyContentHandler(writeLimit)
    }
    val parser = new AutoDetectParser()
    val metadata = new Metadata()

    // To work, we need Tesseract library install natively
    // Input format will not fail, but won't be able to extract text from pictures
    parseContext.set(classOf[TesseractOCRConfig], tesseractConfig)
    parseContext.set(classOf[PDFParserConfig], pdfConfig)
    parseContext.set(classOf[OfficeParserConfig], officeConfig)
    parseContext.set(classOf[Parser], parser)

    val originalSize = -1
    val originalThreshold = ZipInputStreamZipEntrySource.getThresholdBytesForTempFiles
    val originalMinInflateRatio = ZipSecureFile.getMinInflateRatio
    val originalUseTempFilePackageParts = false

    // Reset `IOUtils.BYTE_ARRAY_MAX_OVERRIDE` once text is parsed. Default is -1.
    val byteArrayMaxOverrideConfig = new GenericConfigManager[Int](IOUtils.setByteArrayMaxOverride, originalSize, byteArrayMaxOverride)
    val zipInputStreamZipEntrySourceConfig = new GenericConfigManager[Int](ZipInputStreamZipEntrySource.setThresholdBytesForTempFiles, originalThreshold, 16384)
    val zipSecureFileConfig = new GenericConfigManager[Double](ZipSecureFile.setMinInflateRatio, originalMinInflateRatio, 0)
    val zipPackageSetUseTempFilePackageParts = new GenericConfigManager[Boolean](ZipPackage.setUseTempFilePackageParts, originalUseTempFilePackageParts, true)

    // Create a composite manager
    val compositeManager = CompositeConfigManager(byteArrayMaxOverrideConfig, zipInputStreamZipEntrySourceConfig, zipSecureFileConfig, zipPackageSetUseTempFilePackageParts)

    try compositeManager {
      // Tika will try at best effort to detect MIME-TYPE by reading some bytes in. With some formats such as .docx,
      // Tika is fooled thinking it is just another zip file. In our experience, it always works better when passing
      // a file than a stream as file name is also leveraged. So let's "fool the fool" by explicitly passing filename
      val contentType = retrieveContentType(parser, metadata, stream, filename)

      // Extract content using the appropriate parsing
      parser.parse(stream, handler, metadata, parseContext)
      val extractedTextContent = handler.toString

      // Return extracted content
      TikaContent(
        extractedTextContent,
        contentType,
        metadata.names().map(name => (name, metadata.get(name))).toMap
      )

    } finally {
      stream.close()
    }

  }

  def retrieveContentType(parser: AutoDetectParser,
                          metadata: Metadata,
                          stream: TikaInputStream,
                          filename: String): String = {
    metadata.add(TikaCoreProperties.RESOURCE_NAME_KEY, filename)
    parser.getDetector.detect(stream, metadata).toString
  }

}
