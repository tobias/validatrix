# validatrix

Inspired by error reporting in the Elm language[1] and an article on
configuration feedback for Clojure tooling[2], I looked at what it
would take to provide better feedback when parsing XML configuration
and put together a proof-of-concept that validates against XSDs and
generates friendlier output.

My goals were:

* Give users clear feedback that can be used to correct the
  configuration error without the user having to context-switch to
  documentation, and, in most cases, enable the user to quickly
  identify and understand the issue before looking away from the
  validation output, by:

  * Showing (instead of telling) where in the XML the error occurred

  * Providing richer feedback than the native validation error
    provides (detect potential misspellings, provide alternate
    locations, etc)

  * Showing documentation for the element/attribute where possible
    (pulled from the XSD)

* Use what we already produce (XSDs), without having to create
  additional context-specific schema. Ideally, a project would be able
  to integrate this tool with very little effort.

* Avoid writing a new XSD-based validator, using instead what's
  already provided by the JDK


## Example output

Some examples of the tool in action (validating [3] against [4]):

* detecting a misspelled attribute name:

```
------------------------------- Validation Error -------------------------------

1: <subsystem xmlns="urn:jboss:domain:messaging-activemq:1.0">
2:     <!--<foo/>-->
3:     <server nae="foo" entries="asdf">

               ^ 'nae' isn't an allowed attribute for the 'server' element

4:       <bindings-directory/>
5:       <jornal-directory/>
6:       <jms-queue name="x" entries=""/>

Did you mean 'name'?

Original message:
cvc-complex-type.3.2.2: Attribute 'nae' is not allowed to appear in element 'server'.

--------------------------------------------------------------------------------
```

* detecting a misplaced attribute (could be enhanced to list only potential elements
  that exist in the current document):

```
------------------------------- Validation Error -------------------------------

1: <subsystem xmlns="urn:jboss:domain:messaging-activemq:1.0">
2:     <!--<foo/>-->
3:     <server nae="foo" entries="asdf">

                         ^ 'entries' isn't an allowed attribute for the 'server' element

4:       <bindings-directory/>
5:       <jornal-directory/>
6:       <jms-queue name="x" entries=""/>

'entries' is allowed on elements: 'jms-queue', 'jms-topic', 'connection-factory',
  'legacy-connection-factory', 'pooled-connection-factory'
Did you intend to put it on one of those elements?

Original message:
cvc-complex-type.3.2.2: Attribute 'entries' is not allowed to appear in element 'server'.

--------------------------------------------------------------------------------
```

* detecting a misspelled element:

```
------------------------------- Validation Error -------------------------------

 2:     <!--<foo/>-->
 3:     <server nae="foo" entries="asdf">
 4:       <bindings-directory/>
 5:       <jornal-directory/>

           ^ Element 'jornal-directory' doesn't belong here

 6:       <jms-queue name="x" entries=""/>
 7:
 8:     </server>

Did you mean 'journal-directory'?

Original message:
cvc-complex-type.2.4.a: Invalid content was found starting with element 'jornal-directory'. One of '{"urn:jboss:domain:messaging-activemq:1.0":journal-directory, "urn:jboss:domain:messaging-activemq:1.0":large-messages-directory, "urn:jboss:domain:messaging-activemq:1.0":paging-directory, "urn:jboss:domain:messaging-activemq:1.0":queue, "urn:jboss:domain:messaging-activemq:1.0":security-setting, "urn:jboss:domain:messaging-activemq:1.0":address-setting, "urn:jboss:domain:messaging-activemq:1.0":http-connector, "urn:jboss:domain:messaging-activemq:1.0":remote-connector, "urn:jboss:domain:messaging-activemq:1.0":in-vm-connector, "urn:jboss:domain:messaging-activemq:1.0":connector, "urn:jboss:domain:messaging-activemq:1.0":http-acceptor, "urn:jboss:domain:messaging-activemq:1.0":remote-acceptor, "urn:jboss:domain:messaging-activemq:1.0":in-vm-acceptor, "urn:jboss:domain:messaging-activemq:1.0":acceptor, "urn:jboss:domain:messaging-activemq:1.0":broadcast-group, "urn:jboss:domain:messaging-activemq:1.0":discovery-group, "urn:jboss:domain:messaging-activemq:1.0":cluster-connection, "urn:jboss:domain:messaging-activemq:1.0":grouping-handler, "urn:jboss:domain:messaging-activemq:1.0":divert, "urn:jboss:domain:messaging-activemq:1.0":bridge, "urn:jboss:domain:messaging-activemq:1.0":connector-service, "urn:jboss:domain:messaging-activemq:1.0":jms-queue, "urn:jboss:domain:messaging-activemq:1.0":jms-topic, "urn:jboss:domain:messaging-activemq:1.0":connection-factory, "urn:jboss:domain:messaging-activemq:1.0":legacy-connection-factory, "urn:jboss:domain:messaging-activemq:1.0":pooled-connection-factory}' is expected.

--------------------------------------------------------------------------------
```

* a misplaced element (this could be enhanced to tell you where the
  `bar` element *would* be valid as well):

```
------------------------------- Validation Error -------------------------------

 6:       <jms-queue name="x" entries=""/>
 7:
 8:     </server>
 9:     <bar/>

         ^ Element 'bar' doesn't belong here

10:
11:     <bindings-directory/>
12: </subsystem>

Valid options are: 'server', 'jms-bridge'

Original message:
cvc-complex-type.2.4.a: Invalid content was found starting with element 'bar'. One of '{"urn:jboss:domain:messaging-activemq:1.0":server, "urn:jboss:domain:messaging-activemq:1.0":jms-bridge}' is expected.

--------------------------------------------------------------------------------
```

## Status of the project

At this point, this is a rough proof-of-concept that only handles a
small fraction of the error messages that
javax.xml.validation.Validator can return[5] - validation errors give
a message and a line/column location, so I have to write a custom
parser for each error type to determine the error details. It also
doesn't yet display documentation pulled from the XSD, only knows how
to parse error messages reported in English, and can only provide its
own messages in English.

It also doesn't yet have any integration points that allow it to be
embedded within another application, but that's straightforward to
add.

[1]: http://elm-lang.org/blog/compilers-as-assistants
[2]: http://rigsomelight.com/2016/05/17/good-configuration-feedback-is-essential.html
[3]: https://github.com/tobias/validatrix/blob/master/resources/subsystem.xml
[4]: https://github.com/tobias/validatrix/blob/master/resources/wildfly-messaging-activemq_1_0.xsd
[5]: http://grepcode.com/file/repo1.maven.org/maven2/xerces/xercesImpl/2.11.0/org/apache/xerces/impl/msg/XMLSchemaMessages_en.properties

## License

Copyright © 2016 Red Hat, Inc

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
