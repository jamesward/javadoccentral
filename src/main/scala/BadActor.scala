import zio.*
import zio.concurrent.ConcurrentMap
import zio.direct.*

import java.time.Instant

object BadActor:

  private val banWindow: Duration = 10.seconds
  private val banOnRequestCount = 5

  type IP = String
  type Store = ConcurrentMap[IP, Ref[Chunk[Instant]]]

  val live: ZLayer[Any, Nothing, Store] = ZLayer.fromZIO:
    ConcurrentMap.make[IP, Ref[Chunk[Instant]]]()

  enum Status:
    case Allowed
    case Banned

  given CanEqual[Status, Status] = CanEqual.derived

  // a new request comes in. We need to check if the user has been banned because they made too many suspect requests
  def checkReq(ip: IP, instant: Instant, suspect: Boolean): ZIO[Store, Nothing, Status] =

    // Append to chunk, dropping oldest if at capacity
    def append(chunk: Chunk[Instant], item: Instant): Chunk[Instant] =
      if chunk.size >= banOnRequestCount then chunk.drop(1) :+ item
      else chunk :+ item

    def newEntry(store: Store): ZIO[Any, Nothing, Status] =
      ZIO.when(suspect):
        defer:
          val ref = Ref.make(Chunk(instant)).run
          store.put(ip, ref).run
      .as(Status.Allowed)

    def existingEntry(store: Store)(ref: Ref[Chunk[Instant]]): ZIO[Any, Nothing, Status] =
      defer:
        val items = ref.get.run

        val status = if items.size >= banOnRequestCount then
          val diff = Duration.fromJava(java.time.Duration.between(items.min, items.max))
          if diff <= banWindow then Status.Banned else Status.Allowed
        else
          Status.Allowed

        if status == Status.Allowed && suspect then
          ref.update(append(_, instant)).run

        status

    defer:
      val store = ZIO.service[Store].run
      val maybeUser = store.get(ip).run
      maybeUser.fold(newEntry(store))(existingEntry(store)).run
