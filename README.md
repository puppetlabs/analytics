# Analytics Service

A trapperkeeper web app designed to store analytics for later reporting.
This is a cache that flushes once per day, submitting its cached data to
a data processing pipelin. It will provide a REST endpoint where Puppet
developers can submit their application telemetry. This is designed to 
run where Puppet Server runs.

## Self-service Analytics

If you are looking to add analytics to an application that:
1) runs inside a Puppet install, and
2) is written in Clojure,
see the instructions on the README of the [Analytics Client](https://github.com/puppetlabs/analytics-client).

## Usage

First, run:

    $ lein tk

Then, open a browser and visit: `http://localhost:8080/analytics`

### Running from the REPL

Alternately, run:

    $ lein repl
    nREPL server started on port 52137 on host 127.0.0.1
    user => (go)

This will allow you to launch the app from the Clojure REPL. You can then make
changes and run `(reset)` to reload the app or `(stop)` to shutdown the app.

In addition, the functions `(context)` and `(print-context)` are available to
print out the current trapperkeeper application context. Both of these take an
optional array of keys as a parameter, which is used to retrieve a nested
subset of the context map.

### Querying data in analytics service

To query the data that has been sent to the analytics service, you can
hit the analytics service API. To query events, using the default
settings, query this URL:

    http://localhost:8080/analytics/collections/events

Snapshots can be found here:

    http://localhost:8080/analytics/collections/snapshots
    
Querying stored snapshots in the analytics service might look like this:

    $ curl http://localhost:8080/analytics/collections/snapshots
    [{"hello":{"value":"world","timestamp":"2018-04-04T20:42:00.794Z"}}]

Querying stored events might look like this:

    $ curl http://localhost:8080/analytics/collections/events
    [{"event":"some-event","timestamp":"2018-04-04T21:02:51.784Z"}]

## License

Copyright © 2017 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
