package net.revenj.cache

import java.time.OffsetDateTime
import monix.eval.Task
import monix.reactive.Observable
import monix.reactive.subjects.PublishSubject
import net.revenj.extensibility.SystemState
import net.revenj.patterns.DataChangeNotification.{NotifyWith, Operation}
import net.revenj.patterns._

import java.util.concurrent.Executors
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

class EagerDataCache[T <: Identifiable, PK](
  val name: String,
  repository: Repository[T] with SearchableRepository[T],
  dataChanges: DataChangeNotification,
  systemState: SystemState,
  extractKey: T => PK,
  initialValues: scala.collection.Seq[T] = Nil
) extends DataSourceCache[T] with AutoCloseable {

  protected val cache = new TrieMap[String, T]()
  private var lookup: Map[PK, T] = Map.empty
  private var currentVersion = 0
  private val versionChangeSubject = PublishSubject[Int]()
  private var lastChange = OffsetDateTime.now()
  def changes: Observable[Int] = versionChangeSubject.map(identity)
  def currentLookup: Map[PK, T] = lookup

  private val syncThreadPool = Executors.newFixedThreadPool(1)
  private val syncExecutor = ExecutionContext.fromExecutor(syncThreadPool)

  systemState.change
    .filter(it => it.id == "notification" && it.detail == "started")
    .doOnNext(_ => Task.fromFuture(invalidateAll()))
    .subscribe()(monix.execution.Scheduler.Implicits.global)

  if (initialValues.nonEmpty) {
    set(initialValues)
  } else {
    invalidateAll()
  }

  private val subscription = dataChanges.notifications
    .filter(_.name == name)
    .map { n =>
      val version = currentVersion
      n.operation match {
        case Operation.Insert | Operation.Update =>
          n match {
            case nw: NotifyWith[scala.collection.Seq[T]@unchecked] =>
              if (nw.info != null && nw.info.nonEmpty) {
                change(nw.info, Nil, version, force = true)
              }
            case _ =>
              //TODO: use context from ctor
              implicit val global = scala.concurrent.ExecutionContext.Implicits.global
              repository.find(n.uris).foreach { items => change(items, Nil, version, force = false) }
          }
        case Operation.Change | Operation.Delete =>
          change(Nil, n.uris, version, n.isInstanceOf[NotifyWith[_]])
      }
    }.subscribe()(monix.execution.Scheduler.Implicits.global)

  def set(instances: scala.collection.Seq[T]): Unit = change(instances, Nil, currentVersion, force = true)
  def remove(uris: scala.collection.Seq[String]): Unit = change(Nil, uris, currentVersion, force = true)

  private def change(newInstances: scala.collection.Seq[T], oldUris: scala.collection.Seq[String], oldVersion: Int, force: Boolean): Unit = {
    if (newInstances != null && oldUris != null && (newInstances.nonEmpty || oldUris.nonEmpty)) {
      val shouldInvalidateAll = if (force || oldVersion == currentVersion) {
        val diff = oldUris.diff(newInstances.map(_.URI))
        synchronized {
          val isInvalid = currentVersion != oldVersion
          lastChange = OffsetDateTime.now()
          val newVersion = currentVersion + 1
          newInstances.foreach(f => cache.put(f.URI, f))
          diff.foreach(cache.remove)
          lookup = cache.values.map{ it => extractKey(it) -> it }.toMap
          currentVersion = newVersion
          isInvalid
        }
      } else {
        true
      }
      if (shouldInvalidateAll) {
        invalidateAll()
      } else {
        versionChangeSubject.synchronized {
          versionChangeSubject.onNext(currentVersion)
        }
      }
    }
  }

  def get(uri: String): Option[T] = if (uri != null) cache.get(uri) else None

  private var itemsVersion = -1
  private var cachedItems: scala.collection.IndexedSeq[T] = IndexedSeq.empty
  def items: scala.collection.IndexedSeq[T] = {
    if (currentVersion != itemsVersion) {
      synchronized {
        val version = currentVersion
        if (version != itemsVersion) {
          cachedItems = cache.valuesIterator.toIndexedSeq
          itemsVersion = version
        }
      }
    }
    cachedItems
  }

  def version: Int = currentVersion
  def changedOn: OffsetDateTime = lastChange

  override def invalidate(uris: scala.collection.Seq[String]): Future[Unit] = {
    if (uris != null && uris.nonEmpty) {
      //TODO: use context from ctor
      implicit val global = scala.concurrent.ExecutionContext.Implicits.global
      val version = currentVersion
      repository.find(uris).map { found =>
        change(found, uris, version, force = false)
      }
    } else Future.failed(new RuntimeException("invalid uris provided"))
  }

  override def invalidateAll(): Future[Unit] = {
    Future {
      val version = currentVersion
      val found = Await.result(repository.search(), Duration.Inf)
      change(found, cache.keys.toIndexedSeq, version, force = false)
    }(syncExecutor)
  }

  override def find(uri: String): Future[Option[T]] = {
    Future.successful(get(uri))
  }

  override def find(uris: scala.collection.Seq[String]): Future[scala.collection.IndexedSeq[T]] = {
    if (uris != null) {
      Future.successful(uris.flatMap(get).toIndexedSeq)
    } else {
      Future.failed(new RuntimeException("invalid uris provided"))
    }
  }

  override def search(specification: Option[Specification[T]], limit: Option[Int], offset: Option[Int]): Future[scala.collection.IndexedSeq[T]] = {
    val filtered = if (specification.isDefined) items.filter(specification.get) else items
    val skipped = if (offset.isDefined) filtered.drop(offset.get) else filtered
    Future.successful(if (limit.isDefined) skipped.take(limit.get) else skipped)
  }

  override def count(specification: Option[Specification[T]]): Future[Long] = {
    Future.successful(if (specification.isDefined) items.count(specification.get).toLong else items.size.toLong)
  }

  override def exists(specification: Option[Specification[T]]): Future[Boolean] = {
    Future.successful(if (specification.isDefined) items.exists(specification.get) else items.nonEmpty)
  }

  def close(): Unit = {
    subscription.cancel()
    syncThreadPool.shutdown()
  }
}
