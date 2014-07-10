/*
 Copyright (C) 2013 Typesafe, Inc <http://typesafe.com>
 */
define(['commons/streams', 'services/build', 'services/connection', 'text!./heroku.html', 'css!./heroku.css'],
  function(streams, build, Connection, template){

    var HerokuState = {

    };

    // Make connection a subscriber to events sent to the streams WS
    streams.subscribe(Connection);

    // Reset data previously collected
    Connection.reset();

    return {
      render: function() {
        var $heroku = $(template)[0];
        ko.applyBindings(HerokuState, $heroku);
        return $heroku;
      },
      route: function(url, breadcrumb) {


      }
    }
  });
