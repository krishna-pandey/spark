/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.hive.thriftserver

import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.util.UUID

import scala.collection.JavaConverters._
import scala.collection.mutable

import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hive.service.cli.OperationHandle
import org.apache.hive.service.cli.operation.{GetCatalogsOperation, Operation, OperationManager}
import org.apache.hive.service.cli.session.{HiveSession, HiveSessionImpl, SessionManager}
import org.apache.hive.service.rpc.thrift.{THandleIdentifier, TOperationHandle, TOperationType}

import org.apache.spark.SparkFunSuite

class HiveSessionImplSuite extends SparkFunSuite {
  private var session: HiveSessionImpl = _
  private var operationManager: OperationManagerMock = _

  override def beforeAll() {
    super.beforeAll()

    val sessionManager = new SessionManager(null)
    operationManager = new OperationManagerMock()

    session = new HiveSessionImpl(
      ThriftserverShimUtils.testedProtocolVersions.head,
      "",
      "",
      new HiveConf(),
      ""
    )
    session.setSessionManager(sessionManager)
    session.setOperationManager(operationManager)

    session.open(Map.empty[String, String].asJava)
  }

  test("SPARK-31387 - session.close() closes all sessions regardless of thrown exceptions") {
    val operationHandle1 = session.getCatalogs
    val operationHandle2 = session.getCatalogs

    session.close()

    assert(operationManager.getCalledHandles.contains(operationHandle1))
    assert(operationManager.getCalledHandles.contains(operationHandle2))
  }
}

class GetCatalogsOperationMock(parentSession: HiveSession)
  extends GetCatalogsOperation(parentSession) {

  override def runInternal(): Unit = {}

  override def getHandle: OperationHandle = {
    val uuid: UUID = UUID.randomUUID()
    val tHandleIdentifier: THandleIdentifier = new THandleIdentifier()
    tHandleIdentifier.setGuid(getByteBufferFromUUID(uuid))
    tHandleIdentifier.setSecret(getByteBufferFromUUID(uuid))
    val tOperationHandle: TOperationHandle = new TOperationHandle()
    tOperationHandle.setOperationId(tHandleIdentifier)
    tOperationHandle.setOperationType(TOperationType.GET_TYPE_INFO)
    tOperationHandle.setHasResultSetIsSet(false)
    new OperationHandle(tOperationHandle)
  }

  private def getByteBufferFromUUID(uuid: UUID): Array[Byte] = {
    val bb: ByteBuffer = ByteBuffer.wrap(new Array[Byte](16))
    bb.putLong(uuid.getMostSignificantBits)
    bb.putLong(uuid.getLeastSignificantBits)
    bb.array
  }
}

class OperationManagerMock extends OperationManager {
  private val calledHandles: mutable.Set[OperationHandle] = new mutable.HashSet[OperationHandle]()

  override def newGetCatalogsOperation(parentSession: HiveSession): GetCatalogsOperation = {
    val operation = new GetCatalogsOperationMock(parentSession)
    try {
      val m = classOf[OperationManager].getDeclaredMethod("addOperation", classOf[Operation])
      m.setAccessible(true)
      m.invoke(this, operation)
    } catch {
      case e@(_: NoSuchMethodException | _: IllegalAccessException |
              _: InvocationTargetException) =>
        throw new RuntimeException(e)
    }
    operation
  }

  override def closeOperation(opHandle: OperationHandle): Unit = {
    calledHandles.add(opHandle)
    throw new RuntimeException
  }

  def getCalledHandles: mutable.Set[OperationHandle] = calledHandles
}
