define(['text!./build.html', 'core/pluginapi', 'core/log', 'css!./build.css'], function(template, api, log){

	var ko = api.ko;
	var sbt = api.sbt;

	var Build = api.Widget({
		id: 'build-widget',
		template: template,
		init: function(parameters){
			var self = this

			this.title = ko.observable("Build");
			this.activeTask = ko.observable(""); // empty string or taskId
			this.haveActiveTask = ko.computed(function() {
				return self.activeTask() != "";
			}, this);
			this.needCompile = ko.observable(false);
			this.rebuildOnChange = ko.observable(true);

			this.logModel = new log.Log();

			api.events.subscribe(function(event) {
				return event.type == 'FilesChanged';
			},
			function(event) {
				if (self.rebuildOnChange()) {
					console.log("files changed, doing a rebuild");
					// doCompile just marks a compile pending if one is already
					// active.
					self.doCompile();
				} else {
					console.log("rebuild on change unchecked, doing nothing");
				}
			});

			self.reloadSources(null);
		},
		update: function(parameters){
		},
		// after = optional
		reloadSources: function(after) {
			var self = this;

			self.logModel.info("Refreshing list of source files to watch for changes...");
			sbt.watchSources({
				onmessage: function(event) {
					console.log("event watching sources", event);
					self.logModel.event(event);
				},
				success: function(data) {
					console.log("watching sources result", data);
					self.logModel.info("Will watch " + data.count + " source files.");
					if (typeof(after) === 'function')
						after();
				},
				failure: function(status, message) {
					console.log("watching sources failed", message);
					self.logModel.warn("Failed to reload source file list: " + message);
					if (typeof(after) === 'function')
						after();
				}
			});
		},
		afterCompile: function(succeeded) {
			var self = this;

			if (self.needCompile()) {
				console.log("need to rebuild because something changed while we were compiling");
				self.needCompile(false);
				self.doCompile();
			} else if (succeeded) {
				// asynchronously reload the list of sources in case
				// they changed. we are trying to serialize sbt usage
				// here so we only send our event out when we finish
				// with the reload.
				self.reloadSources(function() {
					// notify others
					api.events.send({ 'type' : 'CompileSucceeded' });
				});
			}
		},
		doCompile: function() {
			var self = this;

			if (self.haveActiveTask()) {
				console.log("Attempt to compile with a compile already active, will rebuild again when we finish");
				self.needCompile(true);
				return;
			}

			self.logModel.clear();
			self.logModel.info("Building...");
			var task = { task: 'CompileRequest' };
			var taskId = sbt.runTask({
				task: task,
				onmessage: function(event) {
					self.logModel.event(event);
				},
				success: function(data) {
					console.log("compile result: ", data);
					self.activeTask("");
					if (data.type == 'CompileResponse') {
						self.logModel.info('Compile complete.');
					} else {
						self.logModel.error('Unexpected reply: ' + JSON.stringify(data));
					}
					self.afterCompile(true); // true=success
				},
				failure: function(status, message) {
					console.log("compile failed: ", status, message)
					self.activeTask("");
					self.logModel.error("Request failed: " + status + ": " + message);
					self.afterCompile(false); // false=failed
				}
			});
			self.activeTask(taskId);
		},
		stopButtonClicked: function(self) {
			if (self.haveActiveTask()) {
				sbt.killTask({
					taskId: self.activeTask(),
					success: function(data) {
						console.log("kill success: ", data)
					},
					failure: function(status, message) {
						console.log("kill failed: ", status, message)
						self.logModel.error("Killing task failed: " + status + ": " + message)
					}
				});
			}
		},
		startButtonClicked: function(self) {
			console.log("Start build was clicked");
			self.doCompile();
		}
	});

	var buildConsole = new Build();

	return api.Plugin({
		id: 'build',
		name: "Build",
		icon: "B",
		url: "#build",
		routes: {
			'build': function() { api.setActiveWidget(buildConsole); }
		},
		widgets: [buildConsole]
	});
});
