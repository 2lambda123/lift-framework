/*
 * Copyright 2010-2017 WorldWide Conferencing, LLC
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
package http
package js

import scala.xml.quote._
import org.specs2.mutable.Specification

import common._
import json._
import JsonDSL._
import util.Helpers._

object HtmlFixerSpec extends Specification  {
  "HtmlFixer" should {
    val testFixer = new HtmlFixer {}
    val testSession = new LiftSession("/context-path", "underlying id", Empty)
    val testRules = new LiftRules()
    testRules.extractInlineJavaScript = true

    "never extract inline JS in fixHtmlFunc" in new WithLiftContext(testRules, testSession) {
      testFixer.fixHtmlFunc("test", xml"""<div onclick="clickMe();"></div>""")(identity) must_==
        """"<div onclick=\"clickMe();\"></div>""""
    }
  }
}
