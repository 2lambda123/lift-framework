/*
* Copyright 2010-2017 WorldWide Conferencing, LLC
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package net.liftweb
package mongodb
package record
package field

import scala.xml.NodeSeq
import net.liftweb.common.{Box, Empty, Failure, Full}
import net.liftweb.json._
import net.liftweb.record.{Field, FieldHelpers, MandatoryTypedField, OptionalTypedField}
import net.liftweb.util.Helpers.tryo
import com.mongodb.{BasicDBList, DBObject}

import scala.collection.JavaConverters._

abstract class JsonObjectTypedField[OwnerType <: BsonRecord[OwnerType], JObjectType <: JsonObject[JObjectType]]
(val owner: OwnerType, valueMeta: JsonObjectMeta[JObjectType])
  extends Field[JObjectType, OwnerType] with MongoFieldFlavor[JObjectType] {

  implicit val formats = owner.meta.formats

  /**
   * Convert the field value to an XHTML representation
   */
  def toForm: Box[NodeSeq] = Empty // FIXME

  /** Encode the field value into a JValue */
  def asJValue: JValue = valueBox.map(_.asJObject) openOr (JNothing: JValue)

  /*
  * Decode the JValue and set the field to the decoded value.
  * Returns Empty or Failure if the value could not be set
  */
  def setFromJValue(jvalue: JValue): Box[JObjectType] = jvalue match {
    case JNothing | JNull if optional_? => setBox(Empty)
    case o: JObject => setBox(tryo(valueMeta.create(o)))
    case other => setBox(FieldHelpers.expectedA("JObject", other))
  }

  def setFromAny(in: Any): Box[JObjectType] = in match {
    case dbo: DBObject => setFromDBObject(dbo)
    case value: JsonObject[_] => setBox(Full(value.asInstanceOf[JObjectType]))
    case Some(value: JsonObject[_]) => setBox(Full(value.asInstanceOf[JObjectType]))
    case Full(value: JsonObject[_]) => setBox(Full(value.asInstanceOf[JObjectType]))
    case (value: JsonObject[_]) :: _ => setBox(Full(value.asInstanceOf[JObjectType]))
    case s: String => setFromString(s)
    case Some(s: String) => setFromString(s)
    case Full(s: String) => setFromString(s)
    case null|None|Empty => setBox(defaultValueBox)
    case f: Failure => setBox(f)
    case o => setFromString(o.toString)
  }

  // parse String into a JObject
  def setFromString(in: String): Box[JObjectType] = tryo(JsonParser.parse(in)) match {
    case Full(jv: JValue) => setFromJValue(jv)
    case f: Failure => setBox(f)
    case _ => setBox(Failure(s"Error parsing String into a JValue: $in"))
  }

  /*
  * Convert this field's value into a DBObject so it can be stored in Mongo.
  */
  def asDBObject: DBObject = JObjectParser.parse(asJValue.asInstanceOf[JObject])

  // set this field's value using a DBObject returned from Mongo.
  def setFromDBObject(dbo: DBObject): Box[JObjectType] =
    setFromJValue(JObjectParser.serialize(dbo).asInstanceOf[JObject])

}

abstract class JsonObjectField[OwnerType <: BsonRecord[OwnerType], JObjectType <: JsonObject[JObjectType]]
(@deprecatedName('rec) owner: OwnerType, valueMeta: JsonObjectMeta[JObjectType])
  extends JsonObjectTypedField(owner, valueMeta) with MandatoryTypedField[JObjectType] {

  def this(owner: OwnerType, valueMeta: JsonObjectMeta[JObjectType], value: JObjectType) = {
    this(owner, valueMeta)
    setBox(Full(value))
  }
}

class OptionalJsonObjectField[OwnerType <: BsonRecord[OwnerType], JObjectType <: JsonObject[JObjectType]]
(owner: OwnerType, valueMeta: JsonObjectMeta[JObjectType])
  extends JsonObjectTypedField(owner, valueMeta) with OptionalTypedField[JObjectType] {

  def this(owner: OwnerType, valueMeta: JsonObjectMeta[JObjectType], valueBox: Box[JObjectType]) = {
    this(owner, valueMeta)
    setBox(valueBox)
  }
}

/*
* List of JsonObject case classes
*/
class JsonObjectListField[OwnerType <: BsonRecord[OwnerType], JObjectType <: JsonObject[JObjectType]]
(owner: OwnerType, valueMeta: JsonObjectMeta[JObjectType])(implicit mf: Manifest[JObjectType])
  extends MongoListField[OwnerType, JObjectType](owner: OwnerType) {

  override def asDBObject: DBObject = {
    val dbl = new BasicDBList
    value.foreach { v => dbl.add(JObjectParser.parse(v.asJObject()(owner.meta.formats))(owner.meta.formats)) }
    dbl
  }

  override def setFromDBObject(dbo: DBObject): Box[List[JObjectType]] =
    setBox(Full(dbo.keySet.asScala.toList.map { k =>
      valueMeta.create(JObjectParser.serialize(dbo.get(k))(owner.meta.formats).asInstanceOf[JObject])(owner.meta.formats)
    }))

  override def asJValue: JValue = JArray(value.map(_.asJObject()(owner.meta.formats)))

  override def setFromJValue(jvalue: JValue) = jvalue match {
    case JNothing | JNull if optional_? => setBox(Empty)
    case JArray(arr) => setBox(Full(arr.map { jv =>
      valueMeta.create(jv.asInstanceOf[JObject])(owner.meta.formats)
    }))
    case other => setBox(FieldHelpers.expectedA("JArray", other))
  }
}

@deprecated("Use the more consistently named 'JsonObjectListField' instead. This class will be removed in Lift 4.", "3.2")
class MongoJsonObjectListField[OwnerType <: BsonRecord[OwnerType], JObjectType <: JsonObject[JObjectType]]
(@deprecatedName('rec) owner: OwnerType, valueMeta: JsonObjectMeta[JObjectType])(implicit mf: Manifest[JObjectType]) extends JsonObjectListField(owner, valueMeta)
