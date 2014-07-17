/*
 Copyright (C) 2013 Typesafe, Inc <http://typesafe.com>
 */
define(['jquery', 'text!./heroku.html', 'css!./heroku.css'], function($, template) {

  var HerokuState = {};

  HerokuState.initialized = ko.observable(false);

  HerokuState.errorMessage = ko.observable();

  HerokuState.herokuEmail = ko.observable();
  HerokuState.herokuPassword = ko.observable();

  HerokuState.logs = ko.observable();

  HerokuState.apps = ko.observableArray([]);

  HerokuState.selectedApp = ko.observable();

  HerokuState.selectedApp.subscribe(function(newValue) {
    HerokuState.setDefaultApp();
  });

  HerokuState.getDefaultApp = function() {
    $.ajax("/api/heroku/apps/default?location=" + window.serverAppModel.location, {
      type: "GET",
      success: function(data) {
        HerokuState.apps().forEach(function(app) {
          if (app.name == data.app) {
            HerokuState.selectedApp(app);
          }
        });
      }
    });
  };

  HerokuState.setDefaultApp = function() {
    if (HerokuState.selectedApp()) {
      $.ajax("/api/heroku/apps/default/" + HerokuState.selectedApp().name + "?location=" + window.serverAppModel.location, {
        type: "PUT",
        error: function (error) {
          console.log(error);
        }
      });
    }
  };

  HerokuState.createNewApp = function() {
    $.ajax("/api/heroku/apps?location=" + window.serverAppModel.location, {
      type: "POST",
      success: function(data) {
        HerokuState.apps.push(data);
        HerokuState.selectedApp(data);
      },
      error: function(error) {
        console.log(error);
      }
    });
  };

  HerokuState.login = function(form) {
    $.ajax("/api/heroku/login?location=" + window.serverAppModel.location, {
      data: JSON.stringify({
        username: HerokuState.herokuEmail(),
        password: HerokuState.herokuPassword()
      }),
      type: "POST",
      contentType: "application/json",
      success: function (data) {
        HerokuState.herokuEmail("");
        HerokuState.herokuPassword("");
        HerokuState.loggedIn(true);
        HerokuState.getApps();
      },
      error: function (error) {
        HerokuState.errorMessage(error.responseJSON.error);
      }
    });
  };

  HerokuState.getApps = function() {
    $.getJSON("/api/heroku/apps?location=" + window.serverAppModel.location).
      success(function(data) {
        HerokuState.initialized(true);
        HerokuState.apps(data);
        HerokuState.getDefaultApp();
        HerokuState.loggedIn(true);
      }).
      error(function(error) {
        HerokuState.initialized(true);
        console.log(error);
      });
  };

  HerokuState.deploy = function(app) {
    var url = "/api/heroku/deploy/" + app.name + "?location=" + window.serverAppModel.location;
    // jquery doesn't support reading chunked responses
    var xhr = new XMLHttpRequest();
    xhr.open("PUT", url, true);
    xhr.onprogress = function () {
      HerokuState.logs(xhr.responseText);
    };
    xhr.send();
  };

  HerokuState.getLogs = function(app) {
    var url = "/api/heroku/logs/" + app.name + "?location=" + window.serverAppModel.location;
    // jquery doesn't support reading chunked responses
    var xhr = new XMLHttpRequest();
    xhr.open("GET", url, true);
    xhr.onprogress = function () {
      HerokuState.logs(xhr.responseText);
    };
    xhr.send();
  };

  HerokuState.loggedIn = ko.observable(false);

  HerokuState.getApps();

  return {
    render: function() {
      var $heroku = $(template)[0];
      ko.applyBindings(HerokuState, $heroku);
      return $heroku;
    }
  };
});
