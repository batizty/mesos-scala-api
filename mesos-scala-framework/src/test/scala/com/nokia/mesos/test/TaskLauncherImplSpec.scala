/* Copyright (c) 2016, Nokia Solutions and Networks Oy
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of Nokia Solutions and Networks Oy nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL NOKIA SOLUTIONS AND NETWORKS OY BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.nokia.mesos.test

import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

import org.apache.mesos.mesos._
import org.apache.mesos.mesos.Value.{ Scalar, Type }
import org.scalamock.scalatest.MockFactory
import org.scalatest.{ FlatSpec, Matchers, OneInstancePerTest }
import org.scalatest.concurrent.ScalaFutures

import com.nokia.mesos.api.async.MesosFramework
import com.nokia.mesos.api.async.TaskLauncher.{ Filter, TaskDescriptor }
import com.nokia.mesos.api.stream.MesosEvents
import com.nokia.mesos.api.stream.MesosEvents.MesosEvent
import com.nokia.mesos.impl.launcher.SimpleScheduling
import com.nokia.mesos.impl.launcher.TaskLauncherImpl

import rx.lang.scala.Subject

class TaskLauncherImplSpec extends FlatSpec with Matchers with ScalaFutures with MockFactory with OneInstancePerTest {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(4.second)

  // set up dependencies
  val mockFw = mock[MesosFramework]
  val mockEvents = Subject[MesosEvent]()
  val mockEventProvider = new MesosEvents { override def events = mockEvents }

  def send(e: MesosEvent): Unit = mockEvents.onNext(e)

  // --== MUT ==--
  val launcher = new TaskLauncherImpl {
    override val fw: MesosFramework = mockFw
    override implicit val executor: ExecutionContext = scala.concurrent.ExecutionContext.global
    override def eventProvider = mockEventProvider
    override val scheduling = new SimpleScheduling
  }

  protected override def withExpectations[T](what: => T): T = {
    def withSleep(): T = {
      val result = what
      // after the test is completed,
      // we wait some more, because
      // some of the mock methods are
      // called by Future callbacks
      Thread.sleep(300)
      result
    }

    super.withExpectations(withSleep())
  }

  // some helpers for test offers, resources and task descriptors
  val defaultSlaveID = SlaveID("slave-1")
  val defaultHost = "slave-1.cluster"
  def resource(name: String) = immutable.Seq(Resource(name, Type.SCALAR, Some(Scalar(5))))
  def offer(id: String, resources: Seq[Resource]) = Offer(OfferID(id), FrameworkID("fw-1"), defaultSlaveID, defaultHost, None, resources)

  def offerEv(id: String, resources: Seq[Resource]) = MesosEvents.Offer(offer(id, resources))
  def offerEv(resourceName: String): MesosEvents.Offer = offerEv(resourceName, resource(resourceName))
  def offersEv(resourceNames: String*) = MesosEvents.Offer(resourceNames.map(resName => offer(resName, resource(resName))): _*)

  // TODO: also test with ContainerInfo
  def taskDescriptor(taskName: String, resourceName: String) =
    TaskDescriptor(taskName, resource(resourceName), Left(CommandInfo()))

  def taskInfo(td: TaskDescriptor) =
    TaskInfo(td.name, TaskID("task-1"), defaultSlaveID, td.resources, command = Some(CommandInfo()))

  // test filters
  val differentSlaveFilter: Filter = m => {
    // they must be on separate slaves:
    (m.keys.map(_.slaveId.value).toSet.size == m.size) && m.forall { case (off, tss) => tss.size <= 1 }
  }

  def slaveIdFilter(slaveId: String): Filter = m => {
    // must be on the given slave
    m.keys.forall {
      _.slaveId.value == slaveId
    }
  }

  // test tasks to be launched
  val taskd1 = taskDescriptor("task 1", "RESOURCE_A")
  val taskd2 = taskDescriptor("task 2", "RESOURCE_B")
  val ti1 = taskInfo(taskd1)
  val ti2 = taskInfo(taskd2)

  "launch" should "decline non matching offers" in {
    (mockFw.launch _).expects(*, *).never
    (mockFw.decline _).expects(OfferID("RESOURCE_X")).once

    val fut = launcher.submitTasks(Seq(taskd1), None)

    send(offerEv("RESOURCE_X"))
    fut.isCompleted should be(false)
  }

  it should "accept an offer" in {
    (mockFw.launch _).expects(Set(OfferID("RESOURCE_A")), *).returns(Seq(Future.successful(ti1)))
    (mockFw.decline _).expects(*).never

    val fut = launcher.submitTasks(Seq(taskd1), None)

    send(offerEv("RESOURCE_A"))
    fut.futureValue should be(Seq(ti1))
  }

  it should "accept a good offer after a bad offer" in {
    (mockFw.launch _).expects(Set(OfferID("RESOURCE_A")), *).returns(Seq(Future.successful(ti1)))
    (mockFw.decline _).expects(OfferID("RESOURCE_X"))

    val fut = launcher.submitTasks(Seq(taskd1), None)

    send(offerEv("RESOURCE_X"))
    fut.isCompleted should be(false)

    send(offerEv("RESOURCE_A"))
    fut.futureValue should be(Seq(ti1))
  }

  it should "decline unused offers" in {
    (mockFw.launch _).expects(Set(OfferID("RESOURCE_A")), *).returns(Seq(Future.successful(ti1)))
    (mockFw.decline _).expects(OfferID("RESOURCE_X"))

    val fut = launcher.submitTasks(Seq(taskd1), None)

    send(offersEv("RESOURCE_A", "RESOURCE_X"))
    fut.futureValue should be(Seq(ti1))
  }

  it should "decline filtered offers" in {
    (mockFw.launch _).expects(*, *).never
    (mockFw.decline _).expects(OfferID("O2")).once

    val fut = launcher.submitTasks(Seq(taskDescriptor("my task", "RESOURCE_A")), Some(slaveIdFilter("s1")))

    send(MesosEvents.Offer(Offer(OfferID("O2"), FrameworkID("fw-1"), SlaveID("s2"), "host", None, resource("RESOURCE_A"))))
    fut.isCompleted should be(false)
  }

  it should "accept offer that matches a given filter" in {
    (mockFw.launch _).expects(Set(OfferID("O2")), *).returns(Seq(Future.successful(ti1)))
    (mockFw.decline _).expects(*).never

    val fut = launcher.submitTasks(Seq(taskDescriptor("my task", "RESOURCE_A")), Some(slaveIdFilter("s2")))

    send(MesosEvents.Offer(Offer(OfferID("O2"), FrameworkID("fw-1"), SlaveID("s2"), "host", None, resource("RESOURCE_A"))))
    fut.futureValue should be(Seq(ti1))
  }

  it should "collect offers for multiple tasks" ignore {
    // TODO launcher could hold on to potentially good offers, e.g.
    // - offer fits one or more but not all tasks -> keep, until some policy
    // - offer does not fit any task -> decline immediately
    (mockFw.launch _).expects(Set(OfferID("RESOURCE_A")), *).returns(Seq(Future.successful(ti1)))
    (mockFw.launch _).expects(Set(OfferID("RESOURCE_B")), *).returns(Seq(Future.successful(ti2)))
    (mockFw.decline _).expects(*).never

    val fut = launcher.submitTasks(Seq(taskd1, taskd2), None)

    send(offerEv("RESOURCE_A"))
    fut.isCompleted should be(false)

    send(offerEv("RESOURCE_B"))
    fut.isCompleted should be(false)
  }

  it should "use multiple offers for multiple tasks" in {
    (mockFw.launch _)
      .expects(Set(OfferID("RESOURCE_A"), OfferID("RESOURCE_B")), *)
      .returns(Seq(Future.successful(ti1), Future.successful(ti2)))
    (mockFw.decline _).expects(*).never

    val fut = launcher.submitTasks(Seq(taskd1, taskd2), None)

    send(offersEv("RESOURCE_A", "RESOURCE_B"))
    fut.futureValue should be(Seq(ti1, ti2))
  }

  it should "decline when filters refuse multiple offers" in {
    (mockFw.launch _).expects(*, *).never
    (mockFw.decline _).expects(OfferID("RESOURCE_A"))
    (mockFw.decline _).expects(OfferID("RESOURCE_B"))

    val fut = launcher.submitTasks(Seq(taskd1, taskd2), Some(differentSlaveFilter))

    send(offersEv("RESOURCE_A", "RESOURCE_B"))

    fut.isCompleted should be(false)
  }

  it should "accept offers that matches filter for multiple tasks" in {
    (mockFw.launch _).expects(Set(OfferID("o1")), *).returns(Seq(Future.successful(ti1)))
    (mockFw.launch _).expects(Set(OfferID("o2")), *).returns(Seq(Future.successful(ti2)))
    (mockFw.decline _).expects(*).never

    val fut = launcher.submitTasks(Seq(taskd1, taskd2), Some(differentSlaveFilter))

    send(MesosEvents.Offer(
      Offer(OfferID("o1"), FrameworkID("fw-1"), SlaveID("s1"), "host1", None, resource("RESOURCE_A")),
      Offer(OfferID("o2"), FrameworkID("fw-1"), SlaveID("s2"), "host2", None, resource("RESOURCE_B"))
    ))

    fut.futureValue should be(Seq(ti1, ti2))
  }

  // TODO: also test multi launch, accept a good offer after a bad offer that is filtered out

  it should "decline offer, when launch throws an exception" in {
    (mockFw.launch _).expects(*, *).throws(new Exception("artifical error"))
    (mockFw.decline _).expects(OfferID("RESOURCE_A"))

    val fut = launcher.submitTasks(Seq(taskd1), None)

    send(offerEv("RESOURCE_A"))
    fut.isCompleted should be(false)
  }
}