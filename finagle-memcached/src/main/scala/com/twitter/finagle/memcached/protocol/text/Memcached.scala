package com.twitter.finagle.memcached.protocol.text

import client.DecodingToResponse
import client.{Decoder => ClientDecoder}
import server.DecodingToCommand
import server.{Decoder => ServerDecoder}
import com.twitter.io.Charsets.Utf8
import com.twitter.finagle._
import com.twitter.finagle.memcached.protocol._
import com.twitter.finagle.memcached.util.ChannelBufferUtils._
import com.twitter.finagle.tracing._
import com.twitter.finagle.stats.{NullStatsReceiver, StatsReceiver}
import org.jboss.netty.buffer.ChannelBuffer
import org.jboss.netty.channel._
import scala.collection.immutable

object Memcached {
  def apply(stats: StatsReceiver = NullStatsReceiver) = new Memcached(stats)
  def get() = apply()
}

object MemcachedClientPipelineFactory extends ChannelPipelineFactory {
  def getPipeline() = {
    val pipeline = Channels.pipeline()

    pipeline.addLast("decoder", new ClientDecoder)
    pipeline.addLast("decoding2response", new DecodingToResponse)

    pipeline.addLast("encoder", new Encoder)
    pipeline.addLast("command2encoding", new CommandToEncoding)
    pipeline
  }
}

object MemcachedServerPipelineFactory extends ChannelPipelineFactory {
  private val storageCommands = collection.Set[ChannelBuffer](
    "set", "add", "replace", "append", "prepend")

  def getPipeline() = {
    val pipeline = Channels.pipeline()

  //        pipeline.addLast("exceptionHandler", new ExceptionHandler)

    pipeline.addLast("decoder", new ServerDecoder(storageCommands))
    pipeline.addLast("decoding2command", new DecodingToCommand)

    pipeline.addLast("encoder", new Encoder)
    pipeline.addLast("response2encoding", new ResponseToEncoding)
    pipeline
  }
}
class Memcached(stats: StatsReceiver) extends CodecFactory[Command, Response] {

  def this() = this(NullStatsReceiver)
  def server = Function.const {
    new Codec[Command, Response] {
      def pipelineFactory = MemcachedServerPipelineFactory
    }
  }

  def client = Function.const {
    new Codec[Command, Response] {
      def pipelineFactory = MemcachedClientPipelineFactory

      // pass every request through a filter to create trace data
      override def prepareConnFactory(underlying: ServiceFactory[Command, Response]) =
        new MemcachedLoggingFilter(stats) andThen underlying

      override def newTraceInitializer = MemcachedTraceInitializer.Module
    }
  }
}

/**
 * Adds tracing information for each memcached request.
 * Including command name, when request was sent and when it was received.
 */
private class MemcachedTracingFilter extends SimpleFilter[Command, Response] {
  def apply(command: Command, service: Service[Command, Response]) = {
    Trace.recordServiceName("memcached")
    Trace.recordRpc(command.name)
    Trace.record(Annotation.ClientSend())

    val response = service(command)
    if (Trace.isActivelyTracing) {
      response onSuccess  {
        case Values(values) =>
          command match {
            case cmd: RetrievalCommand =>
              val keys = immutable.Set(cmd.keys map { _.toString(Utf8) }: _*)
              val hits = values.map {
                case value =>
                  Trace.recordBinary(value.key.toString(Utf8), "Hit")
                  value.key.toString(Utf8)
              }
              val misses = keys -- hits
              misses foreach { k =>
                Trace.recordBinary(k.toString(Utf8), "Miss")
              }
              case _ =>
          }
        case _  =>
      } ensure {
        Trace.record(Annotation.ClientRecv())
      }
    }
    response
  }
}

private class MemcachedLoggingFilter(stats: StatsReceiver)
  extends SimpleFilter[Command, Response] {

  private[this] val serviceName = "memcached"

  private[this] val error = stats.scope("error")
  private[this] val succ  = stats.scope("success")

  override def apply(command: Command, service: Service[Command, Response]) = {
    service(command) map { response =>
      response match {
        case NotFound()
          | Stored()
          | NotStored()
          | Exists()
          | Deleted()
          | NoOp()
          | Info(_, _)
          | InfoLines(_)
          | Values(_)
          | Number(_)      => succ.counter(command.name).incr()
        case Error(_)      => error.counter(command.name).incr()
        case _             => error.counter(command.name).incr()
      }
      response
    }
  }
}
