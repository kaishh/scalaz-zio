package scalaz.zio.syntax
import scalaz.zio.IO

package object defects {

  implicit final class DefectOps[E, A](private val io: IO[E, A]) extends AnyVal {

    /**
     * If you just want to handle exceptions from third-party impure code,
     * please consider [[scalaz.zio.IO#syncThrowable]] first.
     *
     * This function has a lower performance due to forking a fiber.
     *
     * Like [[scalaz.zio.IO#redeem]], but allows recovering from the [[ExitResult.Terminated]] case arising when
     * the computation has been terminated by an unexpected defect or interruption
     */
    def redeem3[E2, B](term: List[Throwable] => IO[E2, B], err: E => IO[E2, B], succ: A => IO[E2, B]): IO[E2, B] =
      io.sandboxed
        .redeem(
          {
            case Left(ts) =>
              term(ts)
            case Right(error) =>
              err(error)
          },
          succ(_)
        )

    /**
     * If you just want to handle exceptions from third-party impure code,
     * please consider [[scalaz.zio.IO#syncThrowable]] first.
     *
     * This function has a lower performance due to forking a fiber.
     *
     * Like [[scalaz.zio.IO#redeemPure]], but allows recovering from the [[ExitResult.Terminated]] case arising when
     * the computation has been terminated by an unexpected defect or interruption
     */
    def redeem3Pure[E2, B](term: List[Throwable] => B, err: E => B, succ: A => B): IO[E2, B] =
      redeem3(term.andThen(IO.now), err.andThen(IO.now), succ.andThen(IO.now))

    /**
     * If you just want to handle exceptions from third-party impure code,
     * please consider [[scalaz.zio.IO#syncThrowable]] first.
     *
     * This function has a lower performance due to forking a fiber.
     *
     * Like [[scalaz.zio.IO#attempt]], but also captures the termination errors arising when
     * the computation has been terminated by an unexpected defect or interruption
     */
    def attempt3: IO[Nothing, Either[Either[List[Throwable], E], A]] =
      redeem3Pure(t => Left(Left(t)), e => Left(Right(e)), Right(_))

    /**
     * If you just want to handle exceptions from third-party impure code,
     * please consider [[scalaz.zio.IO#syncThrowable]] first.
     *
     * This function has a lower performance due to forking a fiber.
     *
     * Recovers ONLY from unexpected defects, NOT from errors.
     * {{{
     *   IO.sync(1 / 0).catchSome {
     *     case _: ArithmeticException => IO.now(9000)
     *   }
     * }}}
     */
    def catchDefect[E1 >: E, A1 >: A](f: List[Throwable] => IO[E1, A1]): IO[E1, A1] =
      redeem3(f, IO.fail, IO.now)

  }

}
