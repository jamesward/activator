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
          console.error(error);
        }
      });
    }
  };

  HerokuState.createNewApp = function() {
    HerokuState.state(HerokuState.STATE_DEPLOY);
    HerokuState.deployLogs("Your app is being created...");
    $.ajax("/api/heroku/apps?location=" + window.serverAppModel.location, {
      type: "POST",
      success: function(data) {
        HerokuState.deployLogs("Your app is being built...");
        var app = {name: data.app.name, web_url: "http://" + data.app.name + ".herokuapp.com"};
        HerokuState.apps.push(app);
        HerokuState.selectedApp(app);
        HerokuState.getBuildLogs(app, data.build.output_stream_url);
      },
      error: function(error) {
        console.error(error);
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
        console.error(error);
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
          console.error(error);
        }
      });
  };

  HerokuState.deploy = function(app) {
    HerokuState.state(HerokuState.STATE_DEPLOY);
    HerokuState.deployLogs("Your app is being uploaded...");

    var url = "/api/heroku/deploy/" + app.name + "?location=" + window.serverAppModel.location;

    $.ajax(url, {
      type: "PUT",
      success: function (data) {
        HerokuState.deployLogs("Your app is being built...");
        HerokuState.getBuildLogs(app, data.output_stream_url);
      },
      error: function (error) {
        console.error(error);
      }
    });
  };

  // todo: when Heroku supports CORS just connect to the url directly
  HerokuState.getBuildLogs = function(app, url) {
    HerokuState.state(HerokuState.STATE_DEPLOY);
    // jquery doesn't support reading chunked responses
    var xhr = new XMLHttpRequest();
    xhr.open("GET", "/api/heroku/build-logs?location=" + window.serverAppModel.location + "&url=" + url, true);
    xhr.onprogress = function () {
      if (xhr.responseText.length > 0) {
        HerokuState.deployLogs(xhr.responseText);
      }
    };
    xhr.send();
  };

  // todo: when Heroku supports CORS just connect to the url directly
  HerokuState.getLogs = function(app) {
    HerokuState.state(HerokuState.STATE_LOGS);
    HerokuState.logs("Fetching logs...");
    var url = "/api/heroku/logs/" + app.name + "?location=" + window.serverAppModel.location;
    // jquery doesn't support reading chunked responses
    var xhr = new XMLHttpRequest();
    xhr.open("GET", url, true);
    xhr.onprogress = function () {
      if (xhr.responseText.length > 0) {
        HerokuState.logs(xhr.responseText);
      }
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
        console.error(error);
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
