/*
 Copyright (C) 2013 Typesafe, Inc <http://typesafe.com>
 */
define(['jquery', 'text!./heroku.html', 'css!./heroku.css'], function($, template) {

  var HerokuState = {};

  HerokuState.STATE_INIT = "init";
  HerokuState.STATE_HOME = "home";
  HerokuState.STATE_LOGIN = "login";
  HerokuState.STATE_DEPLOY = "deploy";
  HerokuState.STATE_LOGS = "logs";
  HerokuState.STATE_CONFIG_VARS = "config_vars";

  HerokuState.state = ko.observable(HerokuState.STATE_INIT);

  HerokuState.loggedIn = ko.observable(false);

  HerokuState.errorMessage = ko.observable();

  HerokuState.herokuEmail = ko.observable();
  HerokuState.herokuPassword = ko.observable();

  HerokuState.deployLogs = ko.observable();

  HerokuState.logs = ko.observable();

  HerokuState.apps = ko.observableArray([]);

  HerokuState.configVarsObject = ko.observable({});
  HerokuState.configVars = ko.computed(function() {
    var a = [];
    for (configVar in HerokuState.configVarsObject()) {
      a.push({name: configVar, value: HerokuState.configVarsObject()[configVar]});
    }
    return a;
  });

  HerokuState.newConfigName = ko.observable();
  HerokuState.newConfigValue = ko.observable();

  HerokuState.selectedApp = ko.observable();

  HerokuState.selectedApp.subscribe(function(newValue) {
    HerokuState.setDefaultApp();
    HerokuState.state(HerokuState.STATE_HOME);
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
        var app = {name: data.app.name, web_url: "http://" + data.app.name + ".herokuapp.com"};
        HerokuState.apps.push(app);
        HerokuState.selectedApp(app);
        HerokuState.state(HerokuState.STATE_DEPLOY);
        HerokuState.getBuildLogs(app, data.build.id);
      },
      error: function(error) {
        // todo: display this somewhere
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

  HerokuState.logout = function(form) {
    $.ajax("/api/heroku/logout?location=" + window.serverAppModel.location, {
      type: "POST",
      success: function (data) {
        HerokuState.apps([]);
        HerokuState.state(HerokuState.STATE_LOGIN);
        HerokuState.selectedApp(undefined);
        HerokuState.loggedIn(false);
        HerokuState.logs("");
        HerokuState.deployLogs("");
        HerokuState.configVars([]);
      },
      error: function (error) {
        console.log(error);
      }
    });
  };

  HerokuState.getApps = function() {
    $.getJSON("/api/heroku/apps?location=" + window.serverAppModel.location).
      success(function(data) {
        HerokuState.state(HerokuState.STATE_HOME);
        HerokuState.apps(data);
        HerokuState.getDefaultApp();
        HerokuState.loggedIn(true);
      }).
      error(function(error) {
        if (error.status == 401) {
          HerokuState.state(HerokuState.STATE_LOGIN);
        }
        else {
          console.log(error);
        }
      });
  };

  HerokuState.deploy = function(app) {
    HerokuState.state(HerokuState.STATE_DEPLOY);
    HerokuState.deployLogs("");
    var url = "/api/heroku/deploy/" + app.name + "?location=" + window.serverAppModel.location;
    // jquery doesn't support reading chunked responses
    var xhr = new XMLHttpRequest();
    xhr.open("PUT", url, true);
    xhr.onprogress = function () {
      HerokuState.deployLogs(xhr.responseText);
    };
    xhr.send();
  };

  HerokuState.getBuildLogs = function(app, id) {
    HerokuState.state(HerokuState.STATE_LOGS);
    var url = "/api/heroku/build-logs/" + app.name + "/" + id + "?location=" + window.serverAppModel.location;
    // jquery doesn't support reading chunked responses
    var xhr = new XMLHttpRequest();
    xhr.open("GET", url, true);
    xhr.onprogress = function () {
      HerokuState.deployLogs(xhr.responseText);
    };
    xhr.send();
  };

  HerokuState.getLogs = function(app) {
    HerokuState.state(HerokuState.STATE_LOGS);
    var url = "/api/heroku/logs/" + app.name + "?location=" + window.serverAppModel.location;
    // jquery doesn't support reading chunked responses
    var xhr = new XMLHttpRequest();
    xhr.open("GET", url, true);
    xhr.onprogress = function () {
      HerokuState.logs(xhr.responseText);
    };
    xhr.send();
  };

  HerokuState.getConfigVars = function(app) {
    $.getJSON("/api/heroku/config-vars/" + app.name + "?location=" + window.serverAppModel.location).
      success(function(data) {
        HerokuState.state(HerokuState.STATE_CONFIG_VARS);
        HerokuState.configVarsObject(data);
      }).
      error(function(error) {
        console.log(error);
      });
  };

  HerokuState.setConfigVars = function() {
    var configVars = {};

    if ((HerokuState.newConfigName() != undefined) && (HerokuState.newConfigValue() != undefined)) {
      configVars[HerokuState.newConfigName()] = HerokuState.newConfigValue();
    }

    HerokuState.configVars().forEach(function(configVar) {
      var value = configVar.value;
      if (value == "") {
        value = null;
      }
      configVars[configVar.name] = value;
    });

    $.ajax("/api/heroku/config-vars/" + HerokuState.selectedApp().name + "?location=" + window.serverAppModel.location, {
      data: JSON.stringify(configVars),
      type: "PATCH",
      contentType: "application/json",
      success: function (data) {
        HerokuState.configVarsObject(data);
        HerokuState.newConfigName(undefined);
        HerokuState.newConfigValue(undefined);
      },
      error: function (error) {
        HerokuState.errorMessage(error.responseJSON.error);
      }
    });
  };

  HerokuState.getApps();

  return {
    render: function() {
      var $heroku = $(template)[0];
      ko.applyBindings(HerokuState, $heroku);
      return $heroku;
    }
  };
});
