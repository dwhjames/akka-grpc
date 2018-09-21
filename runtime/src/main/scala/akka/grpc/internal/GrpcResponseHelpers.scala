/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.grpc.internal

import akka.NotUsed
import akka.annotation.InternalApi
import akka.grpc.scaladsl.headers
import akka.grpc.{ Codec, Grpc, GrpcServiceException, ProtobufSerializer }
import akka.http.scaladsl.model.HttpEntity.LastChunk
import akka.http.scaladsl.model.{ HttpEntity, HttpHeader, HttpResponse }
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import io.grpc.Status

import scala.collection.immutable
import scala.concurrent.Future

/**
 * Some helpers for creating HTTP responses for use with gRPC
 *
 * INTERNAL API
 */
@InternalApi // consumed from generated classes so cannot be private
object GrpcResponseHelpers {
  def apply[T](e: Source[T, NotUsed])(implicit m: ProtobufSerializer[T], mat: Materializer, codec: Codec): HttpResponse =
    GrpcResponseHelpers(e, Source.single(trailer(Status.OK)))

  def apply[T](e: Source[T, NotUsed], status: Future[Status])(implicit m: ProtobufSerializer[T], mat: Materializer, codec: Codec): HttpResponse = {
    implicit val ec = mat.executionContext
    GrpcResponseHelpers(
      e,
      Source
        .lazilyAsync(() ⇒ status.map(trailer(_)))
        .mapMaterializedValue(_ ⇒ NotUsed))
  }

  def apply[T](e: Source[T, NotUsed], trail: Source[HttpEntity.LastChunk, NotUsed])(implicit m: ProtobufSerializer[T], mat: Materializer, codec: Codec): HttpResponse = {
    val outChunks = e
      .map(m.serialize)
      .via(Grpc.grpcFramingEncoder(codec))
      .map(bytes ⇒ HttpEntity.Chunk(bytes))
      .concat(trail)
      .recover {
        case e: GrpcServiceException =>
          trailer(e.status)
        case e: Exception =>
          // TODO handle better
          e.printStackTrace()
          trailer(Status.UNKNOWN.withCause(e).withDescription("Stream failed"))
      }

    HttpResponse(
      headers = immutable.Seq(headers.`Message-Encoding`(codec.name)),
      entity = HttpEntity.Chunked(Grpc.contentType, outChunks))
  }

  def status(status: Status): HttpResponse =
    HttpResponse(entity = HttpEntity.Chunked(Grpc.contentType, Source.single(trailer(status))))

  def trailer(status: Status): LastChunk =
    LastChunk(trailer = statusHeaders(status))

  def statusHeaders(status: Status): List[HttpHeader] =
    List(headers.`Status`(status.getCode.value.toString)) ++ Option(status.getDescription).map(d ⇒ headers.`Status-Message`(d))

}
