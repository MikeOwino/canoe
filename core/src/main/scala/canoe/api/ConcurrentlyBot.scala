package canoe.api

import canoe.api.sources.Polling
import canoe.clients.TelegramClient
import canoe.models.Update
import canoe.models.messages.TelegramMessage
import canoe.scenarios.Scenario
import cats.effect.Concurrent
import cats.effect.concurrent.Ref
import cats.syntax.all._
import fs2.{Pipe, Pull, Stream}

object ConcurrentlyBot {

  def polling[F[_]](implicit C: Concurrent[F], client: TelegramClient[F]): ConcurrentlyBot[F] =
    new ConcurrentlyBot[F](new Polling[F](client))
}

class ConcurrentlyBot[F[_]: Concurrent](source: UpdateSource[F]) {
  def updates: Stream[F, Update] = source.updates

  def follow(scenarios: Scenario[F, Unit]): Stream[F, Update] =
    forkThrough(updates, pipes.messages[F] andThen runScenario(scenarios))

  private def forkThrough[A](stream: Stream[F, A], pipes: Pipe[F, A, Unit]): Stream[F, A] =
    stream.broadcast.zipWithNext.take(1).flatMap {
      case (s1, Some(s2)) => s1.concurrently(s2.through(pipes))
      case (s1, None) => s1
    }


  private def runScenario(scenario: Scenario[F, Unit])(messages: Stream[F, TelegramMessage]): Stream[F, Nothing] = {

    val filterByFirst: Pipe[F, TelegramMessage, TelegramMessage] =
      _.pull.peek1.flatMap {
        case Some((m, rest)) => rest.filter(_.chat.id == m.chat.id).pull.echo
        case None            => Pull.done
      }.stream

    def go(input: Stream[F, TelegramMessage], ids: Ref[F, Set[Long]]): Pull[F, Nothing, Unit] =
      input.pull.peek1.flatMap {
        case Some((m, rest)) =>
          Pull.eval(ids.get.map(_.contains(m.chat.id))).flatMap {
            case true => // contains
              go(rest.tail, ids)

            case false => // doesn't contain
              Pull.eval(ids.update(_ + m.chat.id)) >>
                go(rest.observe(filterByFirst andThen scenario).tail, ids)
          }

        case None => Pull.done
      }

    Stream.eval(Ref.of(Set.empty[Long])).flatMap(ids => go(messages, ids).stream)
  }
}
