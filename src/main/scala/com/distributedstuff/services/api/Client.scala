package com.distributedstuff.services.api

import scala.concurrent.{ExecutionContext, Future}

/**
 * A client is just a wrapper around calls to a service.
 * A client is supposed to know the existing topology of the cluster of services and therefor be able to
 * split the cload across existing instances of a particular service.
 * Client are protocol agnostic because the actual call to the service is performed by the end user in the `call` block.
 */
trait Client {
  /**
   * Async call on a service
   *
   * @param f callback where the call is actually performed. The actual instance of the service is passed as parameter.
   * @param ec execution context for async stuff
   * @tparam T return type for the call
   * @return return of the call
   */
  def call[T](f: Service => T)(implicit ec: ExecutionContext): Future[T]

  /**
   * Async call wrapper an async call on a service
   *
   * @param f callback where the call is actually performed. The actual instance of the service is passed as parameter.
   * @param ec execution context for async stuff
   * @tparam T return type for the call
   * @return return of the call
   */
  def callM[T](f: Service => Future[T])(implicit ec: ExecutionContext): Future[T]
}
