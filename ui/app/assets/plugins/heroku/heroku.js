/*
 Copyright (C) 2013 Typesafe, Inc <http://typesafe.com>
 */
define(['jquery', 'text!./heroku.html', 'css!./heroku.css'], function($, template) {

  var HerokuState = (function() {
    var self = {};

    self.STATE_INIT = "init";
    self.STATE_HOME = "home";
    self.STATE_LOGIN = "login";
    self.STATE_DEPLOY = "deploy";
    self.STATE_LOGS = "logs";
    self.STATE_CONFIG_VARS = "config_vars";

    self.state = ko.observable(self.STATE_INIT);

    self.loggedIn = ko.observable(false);

    self.errorMessage = ko.observable();

    self.herokuEmail = ko.observable();
    self.herokuPassword = ko.observable();

    self.deployLogs = ko.observable();

    self.logs = ko.observable();

    self.apps = ko.observableArray([]);

    self.configVarsObject = ko.observable({});
    self.configVars = ko.computed(function() {
      var a = [];
      for (configVar in self.configVarsObject()) {
        a.push({name: configVar, value: self.configVarsObject()[configVar]});
      }
      return a;
    });

    self.newConfigName = ko.observable();
    self.newConfigValue = ko.observable();

    self.selectedApp = ko.observable();

    self.selectedApp.subscribe(function(newValue) {
      self.setDefaultApp();
      self.state(self.STATE_HOME);
    });

    self.getDefaultApp = function() {
      $.ajax("/api/heroku/apps/default?location=" + window.serverAppModel.location, {
        type: "GET",
        success: function(data) {
          self.apps().forEach(function(app) {
            if (app.name == data.app) {
              self.selectedApp(app);
            }
          });
        }
      });
    };

    self.setDefaultApp = function() {
      if (self.selectedApp()) {
        $.ajax("/api/heroku/apps/default/" + self.selectedApp().name + "?location=" + window.serverAppModel.location, {
          type: "PUT",
          error: function (error) {
            console.error(error);
          }
        });
      }
    };

    self.createNewApp = function() {
      self.state(self.STATE_DEPLOY);
      self.deployLogs("Your app is being created...");
      $.ajax("/api/heroku/app-setup?location=" + window.serverAppModel.location, {
        type: "POST",
        success: function(data) {
          self.deployLogs("Your app is being built...");
          var app = {name: data.app.name, web_url: "http://" + data.app.name + ".herokuapp.com"};
          self.apps.push(app);
          self.selectedApp(app);
          self.state(self.STATE_DEPLOY);
          self.streamLogs(data.build.output_stream_url, self.deployLogs);
        },
        error: function(error) {
          console.error(error);
        }
      });
    };

    self.login = function(form) {
      $.ajax("/api/heroku/login?location=" + window.serverAppModel.location, {
        data: JSON.stringify({
          username: self.herokuEmail(),
          password: self.herokuPassword()
        }),
        type: "POST",
        contentType: "application/json",
        success: function (data) {
          self.herokuEmail("");
          self.herokuPassword("");
          self.loggedIn(true);
          self.getApps();
        },
        error: function (error) {
          self.errorMessage(error.responseJSON.error);
        }
      });
    };

    self.logout = function(form) {
      $.ajax("/api/heroku/logout?location=" + window.serverAppModel.location, {
        type: "POST",
        success: function (data) {
          self.apps([]);
          self.state(self.STATE_LOGIN);
          self.selectedApp(undefined);
          self.loggedIn(false);
          self.logs("");
          self.deployLogs("");
          self.configVars([]);
        },
        error: function (error) {
          console.error(error);
        }
      });
    };

    self.getApps = function() {
      $.getJSON("/api/heroku/apps?location=" + window.serverAppModel.location).
        success(function(data) {
          self.state(self.STATE_HOME);
          self.apps(data);
          self.getDefaultApp();
          self.loggedIn(true);
        }).
        error(function(error) {
          if (error.status == 401) {
            self.state(self.STATE_LOGIN);
          }
          else {
            console.error(error);
          }
        });
    };

    self.deploy = function(app) {
      self.state(self.STATE_DEPLOY);
      self.deployLogs("Your app is being uploaded...");

      var url = "/api/heroku/deploy/" + app.name + "?location=" + window.serverAppModel.location;

      $.ajax(url, {
        type: "PUT",
        success: function (data) {
          self.deployLogs("Your app is being built...");
          self.streamLogs(data.output_stream_url, self.deployLogs);
        },
        error: function (error) {
          console.error(error);
        }
      });
    };

    // todo: when Heroku supports CORS just connect to the url directly
    self.streamLogs = function(url, output) {
      // jquery doesn't support reading chunked responses
      var xhr = new XMLHttpRequest();
      xhr.open("GET", "/api/heroku/log-stream?url=" + url, true);
      xhr.onprogress = function () {
        if (xhr.responseText.length > 0) {
          output(xhr.responseText);
        }
      };
      xhr.onerror = function() {
        // retry
        self.streamLogs(url, output);
      };
      xhr.send();
    };

    self.getLogs = function(app) {
      self.state(self.STATE_LOGS);
      self.logs("Fetching logs...");
      $.getJSON("/api/heroku/logs/" + app.name + "?location=" + window.serverAppModel.location).
        success(function(data) {
          self.streamLogs(data.logplex_url, self.logs);
        }).
        error(function(error) {
          console.error(error);
        });
    };

    self.getConfigVars = function(app) {
      $.getJSON("/api/heroku/config-vars/" + app.name + "?location=" + window.serverAppModel.location).
        success(function(data) {
          self.state(self.STATE_CONFIG_VARS);
          self.configVarsObject(data);
        }).
        error(function(error) {
          console.error(error);
        });
    };

    self.setConfigVars = function() {
      var configVars = {};

      if ((self.newConfigName() != undefined) && (self.newConfigValue() != undefined)) {
        configVars[self.newConfigName()] = self.newConfigValue();
      }

      self.configVars().forEach(function(configVar) {
        var value = configVar.value;
        if (value == "") {
          value = null;
        }
        configVars[configVar.name] = value;
      });

      $.ajax("/api/heroku/config-vars/" + self.selectedApp().name + "?location=" + window.serverAppModel.location, {
        data: JSON.stringify(configVars),
        type: "PATCH",
        contentType: "application/json",
        success: function (data) {
          self.configVarsObject(data);
          self.newConfigName(undefined);
          self.newConfigValue(undefined);
        },
        error: function (error) {
          self.errorMessage(error.responseJSON.error);
        }
      });
    };

    self.getApps();

    return self;
  }());

  return {
    render: function() {
      var $heroku = $(template)[0];
      ko.applyBindings(HerokuState, $heroku);
      return $heroku;
    }
  };
});
