/*
 * Copyright 2006-2017 WorldWide Conferencing, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.liftweb
package util

import java.io._
import java.nio.file.{Files, Path}
import scala.collection.mutable.ListBuffer
import ControlHelpers._
import common._

object IoHelpers extends IoHelpers

trait IoHelpers {
  /**
   * Execute the specified OS command and return the output of that command
   * in a Full Box if the command succeeds, or a Failure if an error occurs.
   */
  def exec(cmds: String*): Box[String] = {
    try {
      class ReadItAll(in: InputStream, done: String => Unit) extends Runnable {
        def run: Unit =  {
          val br = new BufferedReader(new InputStreamReader(in)) // default to platform character set
          val lines = new ListBuffer[String]
          var line = ""
          while (line != null) {
            line = br.readLine
            if (line != null) lines += line
          }
          br.close
          in.close
          done(lines.mkString("\n"))
        }
      }

      var stdOut = ""
      var stdErr = ""
      val proc = Runtime.getRuntime.exec(cmds.toArray)
      val t1 = new Thread(new ReadItAll(proc.getInputStream, x => stdOut = x))
      t1.start
      val t2 = new Thread(new ReadItAll(proc.getErrorStream, x => stdErr = x))
      val res = proc.waitFor
      t1.join
      t2.join
      if (res == 0) Full(stdOut)
      else Failure(stdErr, Empty, Empty)
    } catch {
      case e: Throwable => Failure(e.getMessage, Full(e), Empty)
    }
  }

  /**
   * Read all data to the end of the specified Reader and concatenate
   * the resulting data into a string.
   */
  def readWholeThing(in: Reader): String = {
    val bos = new StringBuilder
    val ba = new Array[Char](4096)

    def readOnce: Unit =  {
      val len = in.read(ba)
      if (len < 0) return
      if (len > 0) bos.appendAll(ba, 0, len)
      readOnce
    }

    readOnce

    bos.toString
  }

  /**
   * Read an entire file into an Array[Byte]
   */
  def readWholeFile(file: File): Array[Byte] = readWholeStream(Files.newInputStream(file.toPath))

  /**
   * Read an entire file into an Array[Byte]
   */
  def readWholeFile(path: Path): Array[Byte] = readWholeStream(Files.newInputStream(path))

  /**
   * Read all data from a stream into an Array[Byte]
   */
  def readWholeStream(in: InputStream): Array[Byte] = {
    val bos = new ByteArrayOutputStream
    val ba = new Array[Byte](4096)

    def readOnce: Unit =  {
      val len = in.read(ba)
      if (len > 0) bos.write(ba, 0, len)
      if (len >= 0) readOnce
    }

    readOnce

    in.close

    bos.toByteArray
  }

  /**
   * Executes by-name function f and then closes the Closeables parameters
   */
  def doClose[T](is: java.io.Closeable*)(f : => T): T = {
    try {
      f
    } finally {
      is.foreach(stream => tryo{ stream.close })
    }
  }
}

/**
 * A trait that defines an Automatic Resource Manager. The ARM
 * allocates a resource (connection to a DB, etc.) when the `exec`
 * method is invoked and releases the resource before the exec method terminates
 *
 * @tparam ResourceType the type of resource allocated
 */
trait AutoResourceManager[ResourceType] {

  /**
   * Execute a block of code with the allocated resource
   *
   * @param f the function that takes the resource and returns a value
   * @tparam T the type of the value returned by the function
   *@return the value returned from the function
   *
   */
  def exec[T](f: ResourceType => T): T
}
