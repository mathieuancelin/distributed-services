package com.distributedstuff.services.clients

import com.distributedstuff.services.api.{Service, Client}
import com.distributedstuff.services.common.Futures

import scala.concurrent.{ExecutionContext, Future}

private class RetryClient(times: Int, underlying: Client) extends Client {

  override def call[T](f: (Service) => T)(implicit ec: ExecutionContext): Future[T] = {
    Futures.retry[T](times)(underlying.call(f)(ec))
  }

  override def callM[T](f: (Service) => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    Futures.retry[T](times)(underlying.callM(f)(ec))
  }
}

object Retry {
  def withExpBackoff(times: Int): (Client) => Client = {
    def f(client: Client): Client = new RetryClient(times, client)
    f
  }
}
