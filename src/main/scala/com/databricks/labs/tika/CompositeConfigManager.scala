package com.databricks.labs.tika

class CompositeConfigManager(configManagers: Seq[GenericConfigManager[_]]) {
  def apply[U](block: => U): U = {
    // Apply all config managers in sequence
    def applyManagers(remaining: List[GenericConfigManager[_]]): U = {
      remaining match {
        case Nil => block // If no more managers, execute the block
        case head :: tail => head(applyManagers(tail)) // Apply the head manager and recurse
      }
    }

    applyManagers(configManagers.toList)
  }
}

object CompositeConfigManager {
  def apply(configManagers: GenericConfigManager[_]*): CompositeConfigManager =
    new CompositeConfigManager(configManagers)
}
