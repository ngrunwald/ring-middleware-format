# ring-middleware-format [![Continuous Integration status](https://secure.travis-ci.org/ngrunwald/ring-middleware-format.png)](http://travis-ci.org/ngrunwald/ring-middleware-format) [![Dependencies Status](http://jarkeeper.com/ngrunwald/ring-middleware-format/status.svg)](http://jarkeeper.com/ngrunwald/ring-middleware-format)

**NOTICE:** For modern HTTP content negotiation, encoding and decoding library, check [Muuntaja](https://github.com/metosin/muuntaja/). Currently there are not plans to implement big changes to Ring-middleware-format.

This is a set of middlewares that can be used to deserialize parameters sent in the :body of requests and serialize a Clojure data structure in the :body of a response to some string or binary representation. It natively handles JSON, MessagePack, YAML, Transit over JSON or Msgpack and Clojure (edn) but it can easily be extended to other custom formats, both string and binary. It is intended for the creation of RESTful APIs that do the right thing by default but are flexible enough to handle most special cases.

## Installation ##

Latest stable version:

[![Clojars Project](http://clojars.org/ring-middleware-format/latest-version.svg)](http://clojars.org/ring-middleware-format)

Add this to your dependencies in `project.clj`.

## Features ##

 - Ring compatible middleware, works with any web framework build on top of Ring
 - Automatically parses requests and encodes responses according to Content-Type and Accept headers
 - Automatically handles charset detection of requests bodies, even if the charset given by the MIME type is absent or wrong (using ICU)
 - Automatically selects and uses the right charset for the response according to the request header
 - Varied formats handled out of the box (*JSON*, *MessagePack*, *YAML*, *EDN*, *Transit over JSON or Msgpack*)
 - Pluggable system makes it easy to add to the standards encoders and decoders custom ones (proprietary format, Protobuf, specific xml, csv, etc.)

## API Documentation ##

<!--Full [API documentation](http://ngrunwald.github.com/ring-middleware-format) is available.-->
API Documentation is not available online. You can clone the repository and run `lein codox` yourself.

## Summary ##

To get automatic deserialization and serialization for all supported formats with sane defaults regarding headers and charsets, just do this:

```clojure
(ns my.app
  (:require [ring.middleware.format :refer [wrap-restful-format]]))

(def app
  (-> handler
      (wrap-restful-format)))
```
`wrap-restful-format` accepts an optional `:formats` parameter, which is a list of the formats that should be handled. The first format of the list is also the default serializer used when no other solution can be found. The defaults are:
```clojure
(wrap-restful-format handler :formats [:json :edn :msgpack :msgpack-kw :yaml :yaml-in-html :transit-json :transit-msgpack])
```

The available formats are:

  - `:json` JSON with string keys in `:params` and `:body-params`
  - `:json-kw` JSON with keywordized keys in `:params` and `:body-params`
  - `:msgpack` [MessagePack format](http://msgpack.org) with string keys.
  - `:msgpack-kw` [MessagePack format](http://msgpack.org) with kwywordized keys.
  - `:yaml` YAML format
  - `:yaml-kw` YAML format with keywordized keys in `:params` and `:body-params`
  - `:edn` edn (native Clojure format). It uses *clojure.tools.edn* and never evals code, but uses the custom tags from `*data-readers*` 
  - `:yaml-in-html` yaml in a html page (useful for browser debugging)
  - `:transit-json` Transit over JSON format
  - `:transit-msgpack` Transit over Msgpack format

Your routes should return raw clojure data structures where everything
inside can be handled by the default encoders (no Java objects or fns
mostly). If a route returns a _String_, _File_, _InputStream_ or _nil_, nothing will be done. If no format can be deduced from the **Accept** header or the format specified is unknown, the first format in the vector will be used (JSON by default).

Please note the default JSON, MessagePack, and YAML decoder do not keywordize their output keys, if this is the behaviour you want (be careful about keywordizing user input!), you should use something like:
```clojure
(wrap-restful-format handler :formats [:json-kw :edn :msgpack-kw :yaml-kw :yaml-in-html :transit-json :transit-msgpack])
```

See also [wrap-restful-format](http://ngrunwald.github.com/ring-middleware-format/ring.middleware.format.html#var-wrap-restful-format) docstring for help on customizing error handling.

It is possible to configure the behavior of various decoders by passing `:response-options` 
or `:params-options` parameters to `(wrap-restful-format)`; these options are structured as 
a map from the type of decoder to the options to use for that format. For example, to pretty-print 
JSON responses these options could be used:
```clojure
(wrap-restful-format handler :formats [:json-kw] :response-options {:json-kw {:pretty true}})
```

## Usage ##

### Detailed Usage ###

You can separate the params and response middlewares. This allows you to use them separately, or to customize their behaviour, with specific error handling for example. See the wrappers docstrings for more details.

```clojure
(ns my.app
  (:require [ring.middleware.format-params :refer [wrap-restful-params]]
            [ring.middleware.format-response :refer [wrap-restful-response]]))

(def app
  (-> handler
      (wrap-restful-params)
      (wrap-restful-response)))
```

### Params Format Middleware ###

These middlewares are mostly lifted from [ring-json-params](https://github.com/mmcgrana/ring-json-params) but generalized for arbitrary decoders. The `wrap-json-params` is drop-in replacement for ring-json-params. They will decode the params in the request body, put them in a `:body-params` key and merge them in the `:params` key if they are a map.
There are six default wrappers:

+ `wrap-json-params`
+ `wrap-json-kw-params`
+ `wrap-yaml-params`
+ `wrap-clojure-params`
+ `wrap-transit-json-params`
+ `wrap-transit-msgpack-params`

There is also a generic `wrap-format-params` on which the others depend. Each of these wrappers take 4 optional args: `:decoder`, `:predicate`, `:binary?` and `:charset`. See `wrap-format-params` docstring for further details.

### Response Format Middleware ###

These middlewares will take a raw data structure returned by a route and serialize it in various formats.

There are six default wrappers:

+ `wrap-json-response`
+ `wrap-yaml-response`
+ `wrap-yaml-in-html-response` (responds to **text/html** MIME type and useful to test an API in the browser)
+ `wrap-clojure-response`
+ `wrap-transit-json-response`
+ `wrap-transit-msgpack-response`

There is also a generic `wrap-format-response` on which the others depend. Each of these wrappers take 4 optional args: `:encoders`, `:predicate`, `binary?` and `:charset`. See `wrap-format-response` docstring for further details.

### Custom formats ###

You can implement custom formats in two ways:

+ If you want to slightly modify an existing wrapper you can juste pass it an argument to overload the default.
For exemple, this will cause all json formatted responses to be encoded in *iso-latin-1*:

```clojure
(-> handler
  (wrap-json-response :charset "ISO-8859-1"))
```
+ You can implement the wrapper from scratch by using either or both `wrap-format-params` and `wrap-format-response`. For now, see the docs of each and how the other formats were implemented for help doing this.

### Charset detection

Icu4j can be used to guess request charset for requests where Content-type header doesn't
define charset, and middleware `:charset` option hasn't been used to set static charset.
To use this feature, add dependency:

```clj
[com.ibm.icu/icu4j "63.1"]
```

Note that icu4j is quite large dependency and will increase your uberjar size by 10MB.

And require separate `guess-charset` namespace which provides function you can use
with wrap-params `:charset` option:

```clj
(ns example
  (:require [ring.middleware.format-params.guess-charset :as guess-charset]
            ...))

(wrap-restful-params ... {:charset guess-charset/get-or-default-charset})
```

## Future Work ##

## See Also ##

This module aims to be both easy to use and easy to extend to new formats. However, it does not try to help with every apect of building a RESTful API, like proper error handling and method dispatching. If that is what you are looking for, you could check the modules which function more like frameworks:

+ [Liberator](https://github.com/clojure-liberator/liberator)

## License ##

Copyright &copy; 2011, 2012, 2013, 2014 Nils Grunwald<br>
Copyright &copy; 2015-2019 Juho Teperi

Distributed under the Eclipse Public License, the same as Clojure.
