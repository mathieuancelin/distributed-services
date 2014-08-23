package com.distributedstuff.services.api

import scala.concurrent.{ExecutionContext, Future}

trait Client {
  def call[T](f: Service => T)(implicit ec: ExecutionContext): Future[T]
  def callM[T](f: Service => Future[T])(implicit ec: ExecutionContext): Future[T]
}
