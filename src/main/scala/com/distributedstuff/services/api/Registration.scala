package com.distributedstuff.services.api

/**
 * A registration of something that you can unregister later.
 */
trait Registration {
  /**
   * Unregister the current registration
   */
  def unregister(): Unit
}