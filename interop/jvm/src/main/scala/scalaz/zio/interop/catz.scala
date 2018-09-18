package scalaz.zio
package interop

import cats.effect._
import cats.syntax.functor._
import cats.{effect, _}
import scalaz.zio.{Fiber, IO}

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

object catz extends CatsInstances

abstract class CatsInstances extends CatsInstances1 {
  implicit def rtsContextShift[E](implicit rts: RTS): ContextShift[IO[E, ?]] = new ContextShift[IO[E, ?]] {
    override def shift: IO[E, Unit] =
      IO.shift(ExecutionContext.fromExecutorService(rts.threadPool))

    override def evalOn[A](ec: ExecutionContext)(fa: IO[E, A]): IO[E, A] = IO.shift(ec) *> fa
  }

//  implicit val taskEffectInstances: Effect[Task] with SemigroupK[Task] =
//    new CatsEffect

//  implicit val taskConcurrentEffectInstances: ConcurrentEffect[Task] with SemigroupK[Task] =
//    new CatsConcurrentEffect

  implicit val taskConcurrentInstances: Concurrent[Task] with Effect[Task] with SemigroupK[Task] =
    new CatsConcurrent
}

sealed abstract class CatsInstances1 extends CatsInstances2 {
  implicit def ioMonoidInstances[E: Monoid]: MonadError[IO[E, ?], E] with Bifunctor[IO] with Alternative[IO[E, ?]] =
    new CatsAlternative[E] with CatsBifunctor
}

sealed abstract class CatsInstances2 {
  implicit def ioInstances[E]: MonadError[IO[E, ?], E] with Bifunctor[IO] with SemigroupK[IO[E, ?]] =
    new CatsMonadError[E] with CatsSemigroupK[E] with CatsBifunctor
}

private class CatsConcurrent extends CatsEffect with Concurrent[Task] {
  private def fiber[A](f: Fiber[Throwable, A]): effect.Fiber[Task, A] = new effect.Fiber[Task, A] {
    override val cancel: Task[Unit] =
//    {
//      for {
//      y <- Promise.make[Nothing, Unit]
//      x = unsafeRun(f.interrupt.fork.flatMap(x => x.onComplete(_ => IO.sync(println(s"ooo fic suc $f")) *> y.complete(()).fork.void) const x))
//      _ = println(s"ho fuck interrupoting thread $f with $x")
//      _ <- IO.sleep(Duration.fromNanos(1000000))
//      _ <- y.get
//      } yield ()
//    }
      f.interrupt.peek(_ => Task(System.out println "interrupt run"))
//       .fork.void // FIXME
//      .fork *> IO.sleep(2.seconds) // FIXME
//    Task(???)

    override val join: Task[A] = f.join
  }

  // FIXME ConcurrentEffect
//  override def liftIO[A](ioa: effect.IO[A]): Task[A] = Async.liftIO(ioa)(this)
  // FIXME ConcurrentEffect
//  override def toIO[A](fa: _root_.scalaz.zio.interop.Task[A]): _root_.cats.effect.IO[A] = Effect.toIOFromRunAsync(fa)(this)

  // default impl ?deadlocks?[not? just interrupt blocking issue?]
  override def cancelable[A](k: (Either[Throwable, A] => Unit) => CancelToken[Task]): Task[A] =
    IO.async0 { kk: Callback[Throwable, A] =>
      val token = k(eitherToExitResult andThen kk)

      scalaz.zio.Async.maybeLaterIO(() => token.catchAll(IO.terminate(_)))
    }

  override def race[A, B](fa: Task[A], fb: Task[B]): Task[Either[A, B]] = fa.raceBoth(fb)

  override def start[A](fa: Task[A]): Task[effect.Fiber[Task, A]] =
    fa.fork.map(fiber).peek(_ => Task(System.out println "start run"))

  override def racePair[A, B](fa: Task[A], fb: Task[B]): Task[Either[(A, effect.Fiber[Task, B]), (effect.Fiber[Task, A], B)]] =
    fa.raceWith(fb)({case (l, f) => IO.now(Left((l, fiber(f))))}, {case (r, f) => IO.now(Right((fiber(f), r)))})

//  override def runCancelable[A](fa: Task[A])(cb: Either[Throwable, A] => effect.IO[Unit]): SyncIO[CancelToken[Task]] =

//    SyncIO(concurrent.Deferred[effect.IO, Task[Unit]](null).flatMap[CancelToken[Task]] { promise =>
//      (runAsync {
//        for {
//          f <- fa.fork
//          _ <- f.onComplete(exit => IO.sync(cb(exitResultToEither(exit)).unsafeRunAsync(_ => ())))
//        } yield f.interrupt
//      } {
//        case Right(c) =>
//          promise.complete(c)
//        case _ => ???
//      }).toIO.flatMap(_ => promise.get)
//    }.unsafeRunSync())

//    runAsync(fa)(cb).as(Task.unit)

//      effect.SyncIO {
//        unsafeRun {
//          fa.fork.flatMap { fiber =>
//            fiber.onComplete { exit =>
////              IO.sync(cb(Right(null.asInstanceOf[A])).unsafeRunAsync(_ => ()))
//              IO.sync(cb(exitResultToEither[A](exit)).unsafeRunAsync(_ => ()))
//            } *> IO.now(fiber.interrupt)
//          }
//        }
//      }

}

private class CatsEffect extends CatsMonadError[Throwable] with Effect[Task] with CatsSemigroupK[Throwable] with RTS {
  protected def exitResultToEither[A]: ExitResult[Throwable, A] => Either[Throwable, A] = {
    case ExitResult.Completed(a)       => Right(a)
    case ExitResult.Failed(t, _)       => Left(t)
    case ExitResult.Terminated(Nil)    => Left(Errors.TerminatedFiber)
    case ExitResult.Terminated(t :: _) => Left(t)
  }

  protected def eitherToExitResult[A]: Either[Throwable, A] => ExitResult[Throwable, A] = {
    case Left(t)  => ExitResult.Failed(t)
    case Right(r) => ExitResult.Completed(r)
  }

  // FIXME
  override def never[A]: Task[A] =
//    Task.unit.forever
//  override def never[A]: Task[A] =
//    ???
  IO.never

  override def runAsync[A](
    fa: Task[A]
  )(cb: Either[Throwable, A] => effect.IO[Unit]): effect.SyncIO[Unit] =
    effect.SyncIO {
      unsafeRunAsync(fa) { exit => cb(exitResultToEither(exit)).unsafeRunAsync(_ => ())
      }
    }.void

  override def async[A](k: (Either[Throwable, A] => Unit) => Unit): Task[A] =
    IO.async { kk: Callback[Throwable, A] => k(eitherToExitResult andThen kk)
    }

//  override def asyncF[A](k: (Either[Throwable, A] => Unit) => Task[Unit]): Task[A] =
//    IO.asyncPure { kk: Callback[Throwable, A] => k(eitherToExitResult andThen kk) }

  override def asyncF[A](k: (Either[Throwable, A] => Unit) => Task[Unit]): Task[A] =
    IO.asyncPure { kk: Callback[Throwable, A] => k(eitherToExitResult andThen kk) }

//  override def runAsync[A](
//    fa: Task[A]
//  )(cb: Either[Throwable, A] => effect.IO[Unit]): effect.SyncIO[Unit] = {
//    val cbZ2C: ExitResult[Throwable, A] => Either[Throwable, A] = {
//      case ExitResult.Completed(a)       => Right(a)
//      case ExitResult.Failed(t, _)       => Left(t)
//      case ExitResult.Terminated(Nil)    => Left(Errors.TerminatedFiber)
//      case ExitResult.Terminated(t :: _) => Left(t)
//    }
//    effect.SyncIO {
//      unsafeRunAsync(fa) {
//        cb.compose(cbZ2C).andThen(_.unsafeRunAsync(_ => ()))
//      }
//    }.void
//  }
//
//  override def async[A](k: (Either[Throwable, A] => Unit) => Unit): Task[A] = {
//    val kk = k.compose[ExitResult[Throwable, A] => Unit] {
//      _.compose[Either[Throwable, A]] {
//        case Left(t)  => ExitResult.Failed(t)
//        case Right(r) => ExitResult.Completed(r)
//      }
//    }
//
//    IO.async(kk)
//  }
//
//  override def asyncF[A](k: (Either[Throwable, A] => Unit) => Task[Unit]): Task[A] = {
//    val kk = k.compose[ExitResult[Throwable, A] => Unit] {
//      _.compose[Either[Throwable, A]] {
//        case Left(t)  => ExitResult.Failed(t)
//        case Right(r) => ExitResult.Completed(r)
//      }
//    }
//
//    IO.asyncPure(kk)
//  }

//  override val threadPool: ExecutorService = {
//      new ThreadPoolExecutor(
//        200 /*min Needs to be at least 200 */, Int.MaxValue,
//        60, TimeUnit.SECONDS,
//        new SynchronousQueue[Runnable](false),
//        Executors.defaultThreadFactory())
//    }
  override def suspend[A](thunk: => Task[A]): Task[A] =
    // Sometimes deadlocks on 'bracket release is called on Completed or Error'
    //  if implemented as
//   IO.flatten(IO.syncThrowable(thunk))
//   thunk
    // ??? (actually, deadlocks like this too, but less often...)
    IO.suspend(
      try {
        thunk
      } catch {
        case NonFatal(e) => IO.fail(e)
      }
    )

  override def bracket[A, B](acquire: Task[A])(use: A => Task[B])(
    release: A => Task[Unit]
  ): Task[B] = IO.bracket(acquire.peek(_ => Task(System.out println "acquire run")))(
    release(_).peek(_ => Task(System.out println "release run")).catchAll(IO.terminate(_)))(
    a => Task(System.out println "use running") *>
    use(a).peek(_ => Task(System.out println "use ran")))

//  override def bracket[A, B](acquire: Task[A])(use: A => Task[B])(
//    release: A => Task[Unit]
//  ): Task[B] = acquire.peek(_ => Task(System.out println "acquire run")).uninterruptibly.flatMap { r =>
//    IO.unit.bracket(_ => release(r).peek(_ => Task(System.out println "release run")).catchAll(IO.terminate(_)))(_ =>
//      use(r).peek(_ => Task(System.out println "use run")))
//  }

  override def bracketCase[A, B](
    acquire: Task[A]
  )(use: A => Task[B])(release: (A, ExitCase[Throwable]) => Task[Unit]): Task[B] =
    acquire.uninterruptibly.flatMap { r =>
    use(r).run.flatMap { exitResult =>
      val exitCase = exitResult match {
        case ExitResult.Completed(_)           => ExitCase.Completed
        case ExitResult.Failed(error, defects) => ExitCase.Error(Errors.UnhandledError(error, defects))
        case ExitResult.Terminated(Nil)        => ExitCase.Error(Errors.TerminatedFiber)
        case ExitResult.Terminated(t :: _)     => ExitCase.Error(t)
      }
      release(r, exitCase)
        .catchAll(IO.terminate(_)) *> (exitResult match {
        case ExitResult.Completed(a)           => IO.now(a)
        case ExitResult.Failed(error, _)       => IO.fail(error)
        case ExitResult.Terminated(Nil)        => IO.terminate(Errors.TerminatedFiber)
        case ExitResult.Terminated(ts)         => IO.terminate0(ts)
      })
    }
    }
}

private class CatsMonad[E] extends Monad[IO[E, ?]] {
  override def pure[A](a: A): IO[E, A]                                 = IO.now(a)
  override def map[A, B](fa: IO[E, A])(f: A => B): IO[E, B]            = fa.map(f)
  override def flatMap[A, B](fa: IO[E, A])(f: A => IO[E, B]): IO[E, B] = fa.flatMap(f)
  override def tailRecM[A, B](a: A)(f: A => IO[E, Either[A, B]]): IO[E, B] =
    f(a).flatMap {
      case Left(l)  => tailRecM(l)(f)
      case Right(r) => IO.now(r)
    }
}

private class CatsMonadError[E] extends CatsMonad[E] with MonadError[IO[E, ?], E] {
  override def handleErrorWith[A](fa: IO[E, A])(f: E => IO[E, A]): IO[E, A] = fa.catchAll(f)
  override def raiseError[A](e: E): IO[E, A]                                = IO.fail(e)
}

// lossy, throws away errors using the "first success" interpretation of SemigroupK
private trait CatsSemigroupK[E] extends SemigroupK[IO[E, ?]] {
  override def combineK[A](a: IO[E, A], b: IO[E, A]): IO[E, A] = a.orElse(b)
}

private class CatsAlternative[E: Monoid] extends CatsMonadError[E] with Alternative[IO[E, ?]] {
  override def combineK[A](a: IO[E, A], b: IO[E, A]): IO[E, A] =
    a.catchAll { e1 =>
      b.catchAll { e2 =>
        IO.fail(Monoid[E].combine(e1, e2))
      }
    }
  override def empty[A]: IO[E, A] = raiseError(Monoid[E].empty)
}

trait CatsBifunctor extends Bifunctor[IO] {
  override def bimap[A, B, C, D](fab: IO[A, B])(f: A => C, g: B => D): IO[C, D] =
    fab.bimap(f, g)
}
