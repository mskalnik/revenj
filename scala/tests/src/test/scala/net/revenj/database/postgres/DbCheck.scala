package net.revenj.database.postgres

import java.awt.Point
import java.awt.geom.Point2D
import java.io.{File, IOException}
import java.sql.{Connection, DriverManager}
import java.util.UUID

import com.dslplatform.compiler.client.parameters._
import com.dslplatform.compiler.client.{Context, Main}
import example.test.Client.Tick
import example.test._
import example.test.postgres._
import javax.sql.DataSource
import monix.eval.Task
import monix.execution.Ack
import monix.reactive.{Observable, Observer}
import net.revenj.database.postgres.converters.IntConverter
import net.revenj.database.postgres.DbCheck.{Db, MyService}
import net.revenj.extensibility.{Container, InstanceScope, SystemState}
import net.revenj.patterns.DataChangeNotification.NotifyInfo
import net.revenj.patterns._
import org.pgscala.embedded.{PostgresCluster, PostgresVersion}
import org.postgresql.copy.CopyManager
import org.postgresql.core.BaseConnection
import org.specs2.ScalaCheck
import org.specs2.concurrent.ExecutionEnv
import org.specs2.matcher.FutureMatchers
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAfterAll

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Random, Success, Try}

class DbCheck extends Specification with BeforeAfterAll with ScalaCheck with FutureMatchers {
  sequential

  var tryDb: Try[PostgresCluster] = _

  def beforeAll(): Unit = {
    tryDb = DbCheck.setupDatabase()
  }

  def afterAll(): Unit = {
    if (tryDb.isSuccess) {
      tryDb.get.stop()
    }
  }

  val jdbcUrl = s"jdbc:postgresql://${Db.Address}:${Db.Port}/${Db.Name}?user=${Db.Role}&password=${Db.Pass}"
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
  implicit val duration: Duration.Infinite = Duration.Inf

  "can start" >> {
    "db initialized" >> {
      tryDb.isSuccess === true
    }
  }
  "simple usage" >> {
    "resolve repo" >> {
      val container = example.Boot.configure(jdbcUrl).asInstanceOf[Container]
      val repoAbc = container.resolve[AbcRepository]
      val repoAbcList = container.resolve[AbcListRepository]
      val oldAbcs = Await.result(repoAbc.search(), Duration.Inf)
      val oldLists = Await.result(repoAbcList.search(), Duration.Inf)
      oldAbcs.size === oldLists.size
      val abc = Abc(s = "defg", abc1 = oldAbcs.lastOption)
      if (oldAbcs.nonEmpty) {
        abc.abc2.enqueue(oldAbcs.last, oldAbcs.head)
      }
      abc.ii = Array(1, 2, 3)
      abc.iii = Some(Array(2, 3, 4))
      abc.iiii = Array(Some(2), None, Some(5))
      abc.ll = Array(0L, 1L, 1000000000000000000L, -1000000000000000000L, -9223372036854775808L, 9223372036854775807L)
      abc.en = En.B
      abc.en2 = Some(En.C)
      abc.en3 = List(En.B)
      abc.ss = Some("xxx")
      abc.sss = List("a", "b", "C")
      abc.ssss = Some(List(Some("x"), None))
      abc.ent1.i = 555
      abc.tt = Some(List(Some(abc.t)))
      val bytes = Array(1, 2, 3, 4).map(_.toByte)
      abc.v = Val(x = Some(5), f = 2.2f, ff = Set(Some(4.5f), None, Some(6.6f)), aa = Some(Another()), en = En.C, bytes = bytes, bb = List(bytes, bytes))
      abc.vv = Some(abc.v)
      abc.vvv = IndexedSeq(abc.v, abc.v)
      abc.ent2 = Array(Ent2(f = 2.2f, ee = Array(Ent4(), Ent4())), Ent2(f = 3.3f))
      val uri = Await.result(repoAbc.insert(abc), Duration.Inf)
      val find = Await.result(repoAbc.find(uri), Duration.Inf)
      container.close()
      uri === abc.URI
      find.isDefined === true
      find.get.en3 === List(En.B)
      find.get.ent2.length === abc.ent2.length
    }
    "data contex usage" >> {
      val container = example.Boot.configure(jdbcUrl).asInstanceOf[Container]
      val ctx = container.resolve[DataContext]
      val oldAbcs = Await.result(ctx.search[Abc](), Duration.Inf)
      val oldLists = Await.result(ctx.search[AbcList](), Duration.Inf)
      val abcSql = Await.result(ctx.search[AbcSql](), Duration.Inf)
      val abc = Abc(s = "defg", abc1 = oldAbcs.lastOption)
      if (oldAbcs.nonEmpty) {
        abc.abc2.enqueue(oldAbcs.last, oldAbcs.head)
      }
      abc.ii = Array(1, 2, 3)
      abc.iii = Some(Array(2, 3, 4))
      abc.iiii = Array(Some(2), None, Some(5))
      abc.ll = Array(0L, 1L, 1000000000000000000L, -1000000000000000000L, -9223372036854775808L, 9223372036854775807L)
      abc.en = En.B
      abc.en2 = Some(En.C)
      abc.en3 = List(En.B)
      abc.ss = Some("xxx")
      abc.sss = List("a", "b", "C")
      abc.ssss = Some(List(Some("x"), None))
      abc.ent1.i = 555
      abc.tt = Some(List(Some(abc.t), None, Some(abc.t.plusDays(1))))
      val bytes = Array(1, 2, 4).map(_.toByte)
      abc.v = Val(x = Some(5), f = 2.2f, ff = Set(Some(4.5f), None, Some(6.6f)), aa = Some(Another()), en = En.C, bytes = bytes, bb = List(bytes, bytes))
      abc.vv = Some(abc.v)
      abc.vvv = IndexedSeq(abc.v, abc.v)
      abc.ent2 = Array(Ent2())
      val uri = abc.URI
      Await.result(ctx.create(abc), Duration.Inf)
      Await.result(ctx.delete(abc), Duration.Inf)
      container.close()
      abc.URI !== uri
    }
    "unit of work usage" >> {
      val container = example.Boot.configure(jdbcUrl).asInstanceOf[Container]
      val ctx = container.resolve[UnitOfWork]
      val oldAbcs = Await.result(ctx.search[Abc](), Duration.Inf)
      val oldLists = Await.result(ctx.search[AbcList](), Duration.Inf)
      val abc = Abc(s = "defg", abc1 = oldAbcs.lastOption)
      if (oldAbcs.nonEmpty) {
        abc.abc2.enqueue(oldAbcs.last, oldAbcs.head)
      }
      abc.ii = Array(1, 2, 3)
      abc.iii = Some(Array(2, 3, 4))
      abc.iiii = Array(Some(2), None, Some(5))
      abc.ll = Array(0L, 1L, 1000000000000000000L, -1000000000000000000L, -9223372036854775808L, 9223372036854775807L)
      abc.en = En.B
      abc.en2 = Some(En.C)
      abc.en3 = List(En.B)
      abc.ss = Some("xxx")
      abc.sss = List("a", "b", "C")
      abc.ssss = Some(List(Some("x"), None))
      abc.ent1.i = 555
      val bytes = Array(1, 4).map(_.toByte)
      abc.v = Val(x = Some(5), f = 2.2f, ff = Set(Some(4.5f), None, Some(6.6f)), aa = Some(Another()), en = En.C, bytes = bytes, bb = List(bytes, bytes))
      abc.vv = Some(abc.v)
      abc.vvv = IndexedSeq(abc.v, abc.v)
      abc.ent2 = Array(Ent2())
      Await.result(ctx.create(abc), Duration.Inf)
      val find = Await.result(ctx.find[Abc](abc.URI), Duration.Inf).get
      Await.result(ctx.commit(), Duration.Inf)
      container.close()
      find.URI === abc.URI
    }
    "persistable sql" >> {
      val container = example.Boot.configure(jdbcUrl).asInstanceOf[Container]
      val ctx = container.resolve[DataContext]
      val abcWrite = Await.result(ctx.search[AbcWrite](), Duration.Inf)
      abcWrite.headOption match {
        case Some(first) =>
          first.en = En.C
          first.ii = Array(12, 3) ++ first.ii
          Await.result(ctx.update(abcWrite), Duration.Inf)
          val find = Await.result(ctx.find[AbcWrite](first.URI), Duration.Inf).get
          container.close()
          find.URI === first.URI
        case _ =>
          1 === 1
      }
    }
    "complex pk" >> {
      val container = example.Boot.configure(jdbcUrl).asInstanceOf[Container]
      val ctx = container.resolve[DataContext]
      val rnd = new Random()
      val cpk = ComplexPk(
        a = rnd.nextInt(),
        b = UUID.randomUUID.toString,
        p = Some(new Point(2, 4)),
        l = Some(new Point2D.Double(2.2, 4.4)),
        p2 = Seq(new Point(1, 5), new Point(5, 6)),
        l2 = Set(Some(new Point2D.Double(-2.2, 5.4)), None, Some(new Point2D.Double(2.3, -5.4))))
      val old = cpk.URI
      Await.result(ctx.create(cpk), Duration.Inf)
      val find = Await.result(ctx.find[ComplexPk](cpk.URI), Duration.Inf)
      old !== cpk.URI
      find.isDefined === true
      find.get.URI == cpk.URI
    }
    "context from connection" >> {
      val container = example.Boot.configure(jdbcUrl).asInstanceOf[Container]
      container.register[MyService](InstanceScope.Transient)
      val ds = container.resolve[DataSource]
      val conn = ds.getConnection
      val service = container.resolve[MyService]
      val ctx = service.factory(conn)
      val abc = Abc(s = "ctx")
      val uri = abc.URI
      Await.result(ctx.create(abc), Duration.Inf)
      conn.close()
      container.close()
      abc.URI !== uri
    }
    "can submit events" >> {
      val container = example.Boot.configure(jdbcUrl).asInstanceOf[Container]
      val ctx = container.resolve[UnitOfWork]
      val ev = TestMe(x = 100, ss = Array("1", "3"), vv = Val(x = Some(5)), vvv = Some(List(Some(Val(x = Some(3))))))
      val total = Await.result(ctx.count[TestMe](), Duration.Inf)
      DbCheck.EventHandlerCounters.resetCounters()
      Await.result(ctx.submit(ev), Duration.Inf)
      ev.URI.length !== 0
      val newTotal = Await.result(ctx.count[TestMe](), Duration.Inf)
      val all = Await.result(ctx.search[TestMe](), Duration.Inf)
      val found = Await.result(ctx.find[TestMe](ev.URI), Duration.Inf)
      found.isDefined === true
      ev.URI === found.get.URI
      Await.result(ctx.commit(), Duration.Inf)
      container.close()
      newTotal == total + 1
      DbCheck.EventHandlerCounters.simpleCounter === 1
      DbCheck.EventHandlerCounters.arrayCounter === 1
      DbCheck.EventHandlerCounters.funcCounter === 1
      DbCheck.EventHandlerCounters.arrayFuncCounter === 1
    }
    "search with spec" >> {
      val container = example.Boot.configure(jdbcUrl).asInstanceOf[Container]
      val ctx = container.resolve[UnitOfWork]
      val x = (new java.util.Date().getTime / 10000).asInstanceOf[Int]
      val ev = TestMe(x = x)
      Await.result(ctx.submit(ev), Duration.Inf)
      val find = Await.result(ctx.search(TestMe.Filter(x, x)), Duration.Inf)
      Await.result(ctx.commit(), Duration.Inf)
      container.close()
      find.size === 1
      find.head.x === x
    }
    "search with spec on sql" >> {
      val container = example.Boot.configure(jdbcUrl).asInstanceOf[Container]
      val ctx = container.resolve[UnitOfWork]
      val x = (new java.util.Date().getTime / 10000).asInstanceOf[Int]
      val abc = Abc(s = "ctx")
      Await.result(ctx.create(abc), Duration.Inf)
      val find = Await.result(ctx.search(AbcSql.Filter(s = "ctx")), Duration.Inf)
      Await.result(ctx.commit(), Duration.Inf)
      container.close()
      find.nonEmpty === true
    }
    "search with nullable timestamp" >> {
      val container = example.Boot.configure(jdbcUrl).asInstanceOf[Container]
      val ctx = container.resolve[UnitOfWork]
      val x = (new java.util.Date().getTime / 10000).asInstanceOf[Int]
      val abc = Abc(s = "xzy")
      Await.result(ctx.create(abc), Duration.Inf)
      val find = Await.result(ctx.search(Abc.Filter(at = abc.t.minusMillis(1))), Duration.Inf)
      Await.result(ctx.commit(), Duration.Inf)
      container.close()
      find.size must beGreaterThanOrEqualTo(1)
    }
    "report test" >> {
      val container = example.Boot.configure(jdbcUrl).asInstanceOf[Container]
      val ctx = container.resolve[UnitOfWork]
      val x = (new java.util.Date().getTime / 10000).asInstanceOf[Int]
      val ev = TestMe(x = x)
      Await.result(ctx.submit(ev), Duration.Inf)
      val rep = ReportMe(x = x)
      val result = Await.result(ctx.populate(rep), Duration.Inf)
      Await.result(ctx.commit(), Duration.Inf)
      container.close()
      result.events.exists(_.URI == ev.URI) === true
    }
    "bool arr pk" >> {
      val container = example.Boot.configure(jdbcUrl).asInstanceOf[Container]
      val ctx = container.resolve[UnitOfWork]
      val root = bpk(b = Array(None, Some(true), Some(false)))
      Await.result(ctx.create(root), Duration.Inf)
      val find = Await.result(ctx.find[bpk](root.URI), Duration.Inf).get
      Await.result(ctx.rollback(), Duration.Inf)
      container.close()
      find.URI === root.URI
      root.b === find.b
    }

  }
  "notifications" >> {
    implicit val scheduler = monix.execution.Scheduler.Implicits.global
    "will raise" >> {
      val container = example.Boot.configure(jdbcUrl).asInstanceOf[Container]
      val changes = container.resolve[DataChangeNotification]
      var changed = false
      val reg = changes.notifications.subscribe(new Observer[NotifyInfo] {
        override def onNext(elem: NotifyInfo): Future[Ack] = {
          changed = true
          Future.successful(Ack.Continue)
        }

        override def onError(ex: Throwable): Unit = ()

        override def onComplete(): Unit = ()
      })
      val ctx = container.resolve[DataContext]
      val ev = TestMe(x = 102)
      changed === false
      Await.result(ctx.submit(ev), Duration.Inf)
      var i = 0
      while (i < 50) {
        if (changed) i = 50
        Thread.sleep(100)
        i += 1
      }
      container.close()
      changed === true
    }
    "can track" >> {
      val container = example.Boot.configure(jdbcUrl).asInstanceOf[Container]
      val changes = container.resolve[DataChangeNotification]
      var changed = false
      changes.track[TestMe].doOnNext { _ =>
        changed = true
        monix.eval.Task.unit
      }.subscribe()
      val ctx = container.resolve[DataContext]
      val ev = TestMe(x = 103)
      changed === false
      Await.result(ctx.submit(ev), Duration.Inf)
      var i = 0
      while (i < 20) {
        if (changed) i = 50
        Thread.sleep(100)
        i += 1
      }
      container.close()
      while (i < 50) {
        if (changed) i = 50
        Thread.sleep(100)
        i += 1
      }
      changed === true
    }
    "can track multiple" >> {
      val container = example.Boot.configure(jdbcUrl).asInstanceOf[Container]
      val changes = container.resolve[DataChangeNotification]
      var changed = 0
      changes.track[TestMe].doOnNext { _ =>
        changed += 1
        monix.eval.Task.unit
      }.subscribe()
      val ctx = container.resolve[DataContext]
      val ev = TestMe(x = 104)
      changed === 0
      Await.result(ctx.submit(ev), Duration.Inf)
      var i = 0
      while (i < 50) {
        if (changed > 0) i = 50
        Thread.sleep(100)
        i += 1
      }
      val ev2 = TestMe(x = 105)
      changed === 2
      Await.result(ctx.submit(ev2), Duration.Inf)
      i = 0
      while (i < 50) {
        if (changed > 1) i = 50
        Thread.sleep(100)
        i += 1
      }
      container.close()
      changed === 4
    }
    "observables" >> {
      val container = example.Boot.configure(jdbcUrl).asInstanceOf[Container]
      val obs1 = container.resolve[Observable[Future[Seq[TestMe]]]]
      val obs2 = container.resolve[Observable[Function0[Future[TestMe]]]]
      val obs3 = container.resolve[Observable[Task[Seq[TestMe]]]]
      var (l1, l2, l3) = (false, false, false)
      obs1.doOnNext { _ => l1 = true; monix.eval.Task.unit }.subscribe()
      obs2.doOnNext { _ => l2 = true; monix.eval.Task.unit }.subscribe()
      obs3.doOnNext { _ => l3 = true; monix.eval.Task.unit }.subscribe()
      val ctx = container.resolve[DataContext]
      val ev = TestMe(x = 101)
      val uri = Await.result(ctx.submit(ev), Duration.Inf)
      var i = 0
      while (i < 50) {
        if (l1 && l2 && l3) i = 50
        Thread.sleep(100)
        i += 1
      }
      container.close()
      l1 === true
      l2 === true
      l3 === true
    }
    "migration" >> {
      val container = example.Boot.configure(jdbcUrl).asInstanceOf[Container]
      val state = container.resolve[SystemState]
      val ds = container.resolve[DataSource]
      val con = ds.getConnection
      var migration = false
      state.change.filter(_.id == "migration").doOnNext { _ =>
        migration = true
        monix.eval.Task.unit
      }.subscribe()
      val stmt = con.createStatement()
      stmt.execute("SELECT pg_notify('migration', 'new')")
      stmt.close()
      con.close()
      var i = 0
      while (i < 50) {
        if (migration) i = 50
        Thread.sleep(100)
        i += 1
      }
      container.close()
      migration === true
    }
  }
  "analysis" >> {
    "simple cube" >> {
      val container = example.Boot.configure(jdbcUrl).asInstanceOf[Container]
      val ds = container.resolve[DataSource]
      val con = ds.getConnection
      val cube = new AbcCube(container)
      val res = cube.analyze(con, Seq(AbcCube.s), Seq(AbcCube.i), Seq(AbcCube.s -> true), None, None, None)
      con.close()
      container.close()
      res.nonEmpty === true
    }
    "cube with filter" >> {
      val container = example.Boot.configure(jdbcUrl).asInstanceOf[Container]
      val ds = container.resolve[DataSource]
      val con = ds.getConnection
      val cube = new AbcCube(container)
      val res = cube.analyze(con, Seq(AbcCube.s), Seq(AbcCube.i, AbcCube.en2), Nil, Some(AbcList.Filter("")), None, None)
      con.close()
      container.close()
      res.nonEmpty === true
    }
    "stream cube " >> {
      val container = example.Boot.configure(jdbcUrl).asInstanceOf[Container]
      val ds = container.resolve[DataSource]
      val con = ds.getConnection
      val cube = new AbcCube(container)
      val res = cube.stream(con, Seq(AbcCube.s), Nil, Nil, None, None, None)
      val hasData = res.next()
      res.close()
      con.close()
      container.close()
      hasData === true
    }
    "cube with report" >> {
      val container = example.Boot.configure(jdbcUrl).asInstanceOf[Container]
      val ds = container.resolve[DataSource]
      val ctx = container.resolve[UnitOfWork]
      val x = (new java.util.Date().getTime / 10000).asInstanceOf[Int]
      val me1 = Me(x = x, s = Some(s"first$x"), a = Seq(1L, 2L), x1 = X(i = 1), x2 = Seq(X(i = 2), X(i = 3)))
      val me2 = Me(x = x + 1, s = Some(s"first$x"), a = Seq(11L, -2L), x1 = X(i = 5), x2 = Seq(X(i = -3)))
      val me3 = Me(x = x + 2, s = Some(s"second$x"), a = Seq(21L, 22L), x1 = X(i = 8), x2 = Seq(X(i = -32), X(i = 13)))
      Await.result(ctx.create(Seq(me1, me2, me3)), Duration.Inf)
      val rep = R(s = s"first$x")
      val result = Await.result(ctx.populate(rep), Duration.Inf)
      Await.result(ctx.rollback(), Duration.Inf)
      container.close()
      result.all.size === 2
      val sorted = result.all.sortBy(_.s.get)
      sorted.head.s === Option(s"first$x")
      sorted.head.x2.size === 3
      sorted.last.s === Option(s"second$x")
      sorted.last.x2.size === 2
      result.x1.size === 1
      result.x2.size === 1
      result.x2.head.x2.size === 5
    }
  }
  "events" >> {
    "aggregate event" >> {
      val container = example.Boot.configure(jdbcUrl).asInstanceOf[Container]
      val ctx = container.resolve[UnitOfWork]
      val rnd = new Random()
      val cl = Client(id = rnd.nextLong(), points = 5)
      Await.result(ctx.create(cl), Duration.Inf)
      Await.result(ctx.submit(Seq(Tick(cl, 3), Tick(cl, 5))), Duration.Inf)
      val found = Await.result(ctx.find[Client](cl.URI), Duration.Inf)
      Await.result(ctx.commit(), Duration.Inf)
      container.close()
      found.isDefined === true
    }
  }
  "rollbacks" >> {
    "uow rollback" >> {
      val container = example.Boot.configure(jdbcUrl).asInstanceOf[Container]
      val uow = container.resolve[UnitOfWork]
      val testMe = TestMe()
      Await.result(uow.submit(testMe), Duration.Inf)
      val uri = testMe.URI
      val found1 = Await.result(uow.find[TestMe](uri), Duration.Inf)
      found1.isDefined === true
      found1.get.URI === uri
      Await.result(uow.rollback(), Duration.Inf)
      val ctx = container.resolve[DataContext]
      val found2 = Await.result(ctx.find[TestMe](uri), Duration.Inf)
      container.close()
      found2.isDefined === false
    }
    "ctx rollback" >> {
      val container = example.Boot.configure(jdbcUrl).asInstanceOf[Container]
      val ds = container.resolve[DataSource]
      val con = ds.getConnection;
      con.setAutoCommit(false)
      val ctxFactory = container.resolve[Connection => DataContext]
      val ctx = ctxFactory(con)
      val testMe = TestMe()
      Await.result(ctx.submit(testMe), Duration.Inf)
      val uri = testMe.URI
      val found1 = Await.result(ctx.find[TestMe](uri), Duration.Inf)
      found1.isDefined === true
      found1.get.URI === uri
      con.rollback()
      val found2 = Await.result(ctx.find[TestMe](uri), Duration.Inf)
      container.close()
      found2.isDefined === false
    }
  }
  "history" >> {
    "can read" >> {
      val container = example.Boot.configure(jdbcUrl).asInstanceOf[Container]
      val repoAbc = container.resolve[AbcRepository]
      val historyRepo = container.resolve[Repository[History[Abc]]]
      val abc = Abc(s = "history")
      abc.ii = Array(1, 2, 3)
      val uri = Await.result(repoAbc.insert(abc), Duration.Inf)
      val hist = Await.result(historyRepo.find(uri), Duration.Inf)
      container.close()
      uri === abc.URI
      hist.isDefined === true
      hist.get.URI === uri
      hist.get.snapshots.size === 1
      hist.get.snapshots.head.action == "Insert"
      hist.get.snapshots.head.value.s == "history"
    }
  }
  "references" >> {
    "simple shallow reference" >> {
      implicit val container = example.Boot.configure(jdbcUrl).asInstanceOf[Container]
      val abc = Abc()
      abc.abc1 = "123"
      container.close()
      abc.abc1ID === Some(123)
    }
    "property will read reference off aggregate" >> {
      val container = example.Boot.configure(jdbcUrl).asInstanceOf[Container]
      val repoAbc = container.resolve[AbcRepository]
      val historyRepo = container.resolve[Repository[History[Abc]]]
      val abc = Abc(s = "history")
      abc.ii = Array(1, 2, 3)
      val abcWrite = AbcWrite()
      abcWrite.refId === None
      abcWrite.ref = Some(abc)
      val oldId = abc.ID
      abcWrite.refId === Some(oldId)
      val uri = Await.result(repoAbc.insert(abc), Duration.Inf)
      container.close()
      oldId !== abc.ID
      abcWrite.refId === Some(abc.ID)
    }
  }
  "snapshot" >> {
    "will read the old value" >> {
      val rnd = new Random()
      val container = example.Boot.configure(jdbcUrl).asInstanceOf[Container]
      val c1 = Client(id = rnd.nextLong(), points = 42)
      val ctx = container.resolve[DataContext]
      Await.result(ctx.create(c1), Duration.Inf)
      val c2 = Client(id = rnd.nextLong(), points = 22, parent = Some(c1), parents = Seq(c1))
      Await.result(ctx.create(c2), Duration.Inf)
      val found = Await.result(ctx.find[Client](c2.URI), Duration.Inf).get
      c2.parentURI === found.parentURI
      c2.parentsURI === found.parentsURI
      c2.parent.map(_.id) === found.parent.map(_.id)
      c2.parent.map(_.points) === Some(42)
      found.parent.map(_.points) === Some(42)
      found.parents.head.points === 42
      c1.points = 55
      Await.result(ctx.update(c1), Duration.Inf)
      val found2 = Await.result(ctx.find[Client](c2.URI), Duration.Inf).get
      container.close()
      c2.parentURI === found2.parentURI
      c2.parentsURI === found2.parentsURI
      found2.parent.map(_.points) === Some(42)
      found2.parents.head.points === 42
    }
  }
}

object DbCheck {
  class ExampleEventHandler extends DomainEventHandler[TestMe] {
    override def handle(domainEvent: TestMe): Unit = {
      if (domainEvent.URI.isEmpty) {
        EventHandlerCounters.simpleCounter += 1
      }
    }
  }

  class ExampleArrayEventHandler extends DomainEventHandler[Array[TestMe]] {
    override def handle(events: Array[TestMe]): Unit = {
      if (!events.exists(_.URI.nonEmpty)) {
        EventHandlerCounters.arrayCounter += 1
      }
    }
  }

  class ExampleFuncEventHandler extends DomainEventHandler[Function0[TestMe]] {
    override def handle(event: Function0[TestMe]): Unit = {
      if (event().URI.nonEmpty) {
        EventHandlerCounters.funcCounter += 1
      }
    }
  }

  class ExampleFuncArrayEventHandler extends DomainEventHandler[Function0[Array[TestMe]]] {
    override def handle(events: Function0[Array[TestMe]]): Unit = {
      if (!events().exists(_.URI.isEmpty)) {
        EventHandlerCounters.arrayFuncCounter += 1
      }
    }
  }

  object EventHandlerCounters {
    var simpleCounter = 0
    var arrayCounter = 0
    var funcCounter = 0
    var arrayFuncCounter = 0

    def resetCounters(): Unit = {
      simpleCounter = 0
      arrayCounter = 0
      funcCounter = 0
      arrayFuncCounter = 0
    }
  }

  class MyService(val factory: Connection => DataContext)

  private class TestContext extends Context {
    val error = new StringBuilder

    override def show(values: String*): Unit = {}

    override def log(value: String): Unit = {}

    override def log(value: Array[Char], len: Int): Unit = {}

    override def error(value: String): Unit = {
      error.append(value)
    }

    override def error(ex: Exception): Unit = {
      error.append(ex.getMessage)
    }
  }

  object Db {
    val Address = "127.0.0.1"
    val Port = 5555

    val Catalog = "postgres"
    val Name = "revenj"

    val Role = "revenj"
    val Pass = "revenj"
  }

  def setupDatabase(): Try[PostgresCluster] = {
    try {
      // initialise cluster
      val postgres = new PostgresCluster(PostgresVersion.`11`, new File("target/dbcheck").getCanonicalFile, Map(
        "listen_addresses" -> s"'${Db.Address}'",
        "port" -> s"${Db.Port}",
      ))
      postgres.initialize(Db.Role, Db.Pass)
      val (_, clusterReady) = postgres.start()
      Await.result(clusterReady, 60.seconds)

      // initialise the role & database
      Class.forName("org.postgresql.Driver")
      val connection = DriverManager.getConnection(s"jdbc:postgresql://${Db.Address}:${Db.Port}/${Db.Catalog}?user=${Db.Role}&password=${Db.Pass}")
      try {
        val stmt = connection.createStatement()
        try {
          stmt.execute(s"""CREATE DATABASE ${Db.Name} OWNER ${Db.Role} ENCODING 'utf8' TEMPLATE template1""")
        } finally {
          stmt.close()
        }
      } finally {
        connection.close()
      }

      val context = new TestContext
      context.put(Download.INSTANCE, "")
      context.put(Force.INSTANCE, "")
      context.put(ApplyMigration.INSTANCE, "")
      context.put(DisablePrompt.INSTANCE, "")
      context.put(PostgresConnection.INSTANCE, s"${Db.Address}:${Db.Port}/${Db.Name}?user=${Db.Role}&password=${Db.Pass}")
      val file = getClass.getResource("/model.dsl")
      context.put(DslPath.INSTANCE, file.getFile)
      val params = Main.initializeParameters(context, ".")
      if (!Main.processContext(context, params)) {
        Thread.sleep(2000)
        context.error.setLength(0)
        if (!Main.processContext(context, params)) {
          throw new IOException("Unable to migrate database: " + context.error.toString)
        }
      }
      Success(postgres)
    } catch {
      case ex: Throwable =>
        ex.printStackTrace()
        Failure(ex)
    }
  }
}