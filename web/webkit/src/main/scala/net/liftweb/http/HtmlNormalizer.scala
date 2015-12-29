package net.liftweb
package http

import scala.xml._
import scala.collection.immutable.Vector

import js.JsCmd
import js.JsCmds.Noop
import js.JE.{AnonFunc, Call, JsRaw}

import util.Helpers._

/**
 * Represents an HTML attribute for an event handler. Carries the event name and
 * the JS that should run when that event is triggered as a String.
 */
private case class EventAttribute(eventName: String, jsString: String)
private object EventAttribute {
  /**
   * Some elements allow a URL attribute to take a `javascript:(//)`-style
   * URL instead of setting an `on*` event in order to invoke JS. For example,
   * you can (and Lift does) set a form's `action` attribute to
   * `javascript://(some JS)` instead of setting `onsubmit` to `(someJS);
   * return false`.
   *
   * This is a map from those attribute names to the corresponding JS event
   * that would be used to execute that JS when it isn't run in line.
   */
  val eventsByAttributeName =
    Map(
      "action" -> "submit",
      "href" -> "click"
    )

  object EventForAttribute {
    def unapply(attributeName: String): Option[String] = {
      eventsByAttributeName.get(attributeName)
    }
  }
}

/**
 * Helper class that performs certain Lift-specific normalizations of HTML
 * represented as `NodeSeq`s:
 *  - Fixes URLs for `link`, `a`, `form`, and `script` elements to properly
 *    prepend the container's context path in case these are absolute URLs.
 *  - Extracts event attributes (replacing them with
 *    `[[LiftRules.attributeForRemovedEventAttributes]]` if needed), returning
 *    instead JavaScript that will attach the corresponding event handler to
 *    that element.
 *  - Provides for caller-specific additional processing for each node.
 */
private[http] final object HtmlNormalizer {
  // Fix URLs using Req.normalizeHref and extract JS event attributes for
  // putting into page JS. Returns a triple of:
  //  - The optional id that was found in this set of attributes.
  //  - The normalized metadata.
  //  - A list of extracted `EventAttribute`s.
  private def normalizeUrlAndExtractEvents(
    attributeToNormalize: String,
    attributes: MetaData,
    contextPath: String,
    shouldRewriteUrl: Boolean, // whether to apply URLRewrite.rewriteFunc
    eventAttributes: List[EventAttribute] = Nil
  ): (Option[String], MetaData, List[EventAttribute]) = {
    if (attributes == Null) {
      (None, Null, eventAttributes)
    } else {
      // Note: we don't do this tail-recursively because we have to preserve
      // attribute order!
      val (id, normalizedRemainingAttributes, remainingEventAttributes) =
        normalizeUrlAndExtractEvents(
          attributeToNormalize,
          attributes.next,
          contextPath,
          shouldRewriteUrl,
          eventAttributes
        )

      attributes match {
        case attribute @ UnprefixedAttribute(
               EventAttribute.EventForAttribute(eventName),
               attributeValue,
               remainingAttributes
             ) if attributeValue.text.startsWith("javascript:") =>
          val attributeJavaScript = {
            // Could be javascript: or javascript://.
            val base = attributeValue.text.substring(11)
            val strippedJs =
              if (base.startsWith("//"))
                base.substring(2)
              else
                base

            if (strippedJs.trim.isEmpty) {
              Nil
            } else {
              // When using javascript:-style URIs, event.preventDefault is implied.
              List(strippedJs + "; event.preventDefault();")
            }
          }

          val updatedEventAttributes =
            attributeJavaScript.map(EventAttribute(eventName, _)) :::
            remainingEventAttributes

          (id, normalizedRemainingAttributes, updatedEventAttributes)

        case UnprefixedAttribute(name, _, _) if name == attributeToNormalize =>
          val normalizedUrl =
            Req.normalizeHref(
              contextPath,
              attributes.value,
              shouldRewriteUrl,
              URLRewriter.rewriteFunc
            )

          val newMetaData =
            new UnprefixedAttribute(
              attributeToNormalize,
              normalizedUrl,
              normalizedRemainingAttributes
            )

          (id, newMetaData, remainingEventAttributes)

        case UnprefixedAttribute(name, attributeValue, _) if name.startsWith("on") =>
          val updatedEventAttributes =
            EventAttribute(name.substring(2), attributeValue.text) ::
            remainingEventAttributes

          (id, normalizedRemainingAttributes, updatedEventAttributes)

        case UnprefixedAttribute("id", attributeValue, _) =>
          val idValue = Option(attributeValue.text).filter(_.nonEmpty)

          (idValue, attributes.copy(normalizedRemainingAttributes), remainingEventAttributes)

        case _ =>
          (id, attributes.copy(normalizedRemainingAttributes), remainingEventAttributes)
      }
    }
  }

  // Given an element id and the `EventAttribute`s to apply to elements with
  // that id, return a JsCmd that binds all those event handlers to that id.
  private def jsForEventAttributes(elementId: String, eventAttributes: List[EventAttribute]): JsCmd = {
    eventAttributes.map {
      case EventAttribute(name, handlerJs) =>
        Call(
          "lift.onEvent",
          elementId,
          name,
          AnonFunc("event", JsRaw(handlerJs).cmd)
        ).cmd
    }.foldLeft(Noop)(_ & _)
  }

  private def normalizeElementAndAttributes(element: Elem, attributeToNormalize: String, contextPath: String, shouldRewriteUrl: Boolean): (Elem, JsCmd) = {
    val (id, normalizedAttributes, eventAttributes) =
      normalizeUrlAndExtractEvents(
        attributeToNormalize,
        element.attributes,
        contextPath,
        shouldRewriteUrl
      )

    val attributesIncludingEventsAsData =
      LiftRules.attributeForRemovedEventAttributes match {
        case Some(attribute) if eventAttributes.nonEmpty =>
          val removedAttributes = eventAttributes.map {
            case EventAttribute(event, _) =>
              s"on$event"
          }
          new UnprefixedAttribute(attribute, removedAttributes.mkString(" "), normalizedAttributes)

        case _ =>
          normalizedAttributes
      }

    id.map { foundId =>
      (
        element.copy(attributes = attributesIncludingEventsAsData),
        jsForEventAttributes(foundId, eventAttributes)
      )
    } getOrElse {
      if (eventAttributes.nonEmpty) {
        val generatedId = s"lift-event-js-${nextFuncName}"

        (
          element.copy(attributes = new UnprefixedAttribute("id", generatedId, attributesIncludingEventsAsData)),
          jsForEventAttributes(generatedId, eventAttributes)
        )
      } else {
        (
          element.copy(attributes = attributesIncludingEventsAsData),
          Noop
        )
      }
    }
  }
  
  /**
   * Base for all the normalizeHtml* implementations; in addition to what it
   * usually does, takes an `[[additionalChanges]]` function that is passed a
   * state object and the current (post-normalization) node and can adjust the
   * state and tweak the normalized nodes or even add more JsCmds to be
   * included.  That state is in turn passed to any invocations for any of the
   * children of the current node. Note that state is '''not''' passed back up
   * the node hierarchy, so state updates are '''only''' seen by children of
   * the node.
   *
   * See `[[LiftMerge.merge]]` for sample usage.
   */
  def normalizeHtmlAndEventHandlers[State](
    nodes: NodeSeq,
    contextPath: String,
    stripComments: Boolean,
    state: State,
    additionalChanges: (State, Elem)=>(State, NodeSeq, JsCmd)
  ): (NodeSeq, JsCmd) = {
    nodes.foldLeft((Vector[Node](), Noop): (NodeSeq, JsCmd)) { (soFar, nodeToNormalize) =>
      (soFar match {
        case (normalizedNodes, extractedJs) =>
          nodeToNormalize match {
            case element: Elem =>
              val (attributeToFix, shouldRewriteUrl) =
                element.label match {
                  case "form" =>
                    ("action", true)

                  case "a" =>
                    ("href", true)
                  case "link" =>
                    ("href", false)

                  case "script" =>
                    ("src", false)
                  case _ =>
                    ("src", true)
                }

              val (normalizedElement, elementJsCmds) =
                normalizeElementAndAttributes(
                  element,
                  attributeToFix,
                  contextPath,
                  shouldRewriteUrl
                )

              val (nextState, customResult, additionalJsCmds) =
                additionalChanges(state, normalizedElement)

              customResult match {
                case element: Elem =>
                  val (newChildren, childJsCmds) =
                    normalizeHtmlAndEventHandlers(element.child, contextPath, stripComments, nextState, additionalChanges)

                  (
                    normalizedNodes.+:[Node,NodeSeq](element.copy(child = newChildren)),
                    extractedJs & elementJsCmds & additionalJsCmds & childJsCmds
                  )

                case _ =>
                  (
                    normalizedNodes ++ customResult,
                    extractedJs & elementJsCmds & additionalJsCmds
                  )
              }
            case Group(groupNodes) =>
              val (normalizedGroupNodes: NodeSeq, js: JsCmd) =
                normalizeHtmlAndEventHandlers(
                  groupNodes,
                  contextPath,
                  stripComments,
                  state,
                  additionalChanges
                )

              (normalizedNodes.+:[Node,NodeSeq](Group(normalizedGroupNodes)), extractedJs & js)

            case c: Comment if stripComments =>
              (normalizedNodes, extractedJs)

            case _ =>
              (normalizedNodes.+:[Node,NodeSeq](nodeToNormalize): NodeSeq, extractedJs)
          }
      }): (NodeSeq, JsCmd)
    }
  }
}
