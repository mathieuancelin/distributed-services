package com.distributedstuff.services.clients.command

class TooManyConcurrentRequestsException(message: String = "") extends Throwable(message)

class ServiceDescNotFoundException(message: String = "") extends Throwable(message)

class CircuitOpenException(message: String = "") extends Throwable(message)