"use strict";

var hasPermissions = false;
var RUNNING = 0;
var NOT_RUNNING = 1;
var STARTING = 2;
var STOPPING = 3;

ons.ready(
	function () {
		requestPermissions();
		checkService();
	}
);


document.addEventListener(
	"click", 
	function (event) {
		let action = event.target && event.target.getAttribute("data-action");
		if (!action) return;
		switch (action) {
			case "check-permissions":
				requestPermissions();
				break;
			case "toggle-service":
				toggleService();
				break
		}
	}
);

function getValue(id) {
	var el = document.getElementById(id);
	return el ? el.value : "";
}

async function requestPermissions() {
	markButton("check-permissions", null, "Checking ...");
	let sms = await cordova.plugins.GatewayPlugin.requestSmsPermission();
	let notification = await cordova.plugins.GatewayPlugin.requestNotificationPermission();
	let overlay = await cordova.plugins.GatewayPlugin.requestOverlayPermission();
	let battery = await cordova.plugins.GatewayPlugin.requestBatteryOptimization();
	setTimeout(
		function(){
			if(sms.granted == true && notification.granted==true && overlay.granted==true && battery.unrestricted==true){
				markButton("check-permissions", true, "Granted");
				hasPermissions = true;
			}else{
				markButton("check-permissions", false, "Check Permissions");
				hasPermissions = false;
			}
		},
		2500
	);	
}

async function checkService() {
	let s = await getServiceState();
	updateServiceButton(s?RUNNING:NOT_RUNNING);
}

async function toggleService() {
	let running = await getServiceState();
	if(running){	
		updateServiceButton(STOPPING);	
		await stopService();
		setTimeout(function(){updateServiceButton(NOT_RUNNING);},2500);
	} else {
		updateServiceButton(STARTING);
		await startService();
		setTimeout(function(){updateServiceButton(RUNNING);},2500);
	}
}

function markButton(id, ok, label) {
	let b = document.getElementById(id);
	b.classList.remove("success", "danger", "muted");
	if (ok===true) {
		b.textContent = label + " ✓";
		b.classList.add("success");
	} else if (ok===false){
		b.textContent = label + " ✕";
		b.classList.add("danger");
	} else {
		b.textContent = label;
		b.classList.add("muted");
	}
}

function updateServiceButton(type) {
	let b = document.getElementById("serviceToggleButton");
	b.classList.remove("success", "danger", "muted");
	if(type==RUNNING){
		b.textContent = "Stop Service";
		b.classList.add("danger");
	}else if(type==NOT_RUNNING){
		b.textContent = "Start Service";
		b.classList.add("success");
	} else if(type==STARTING){
		b.textContent = "Starting Service ...";
		b.classList.add("muted");
	} else if(type==STOPPING){
		b.textContent = "Stopping Service ...";
		b.classList.add("muted");
	}
}

async function startService() {
	updateServiceButton(STARTING);
	let s = await cordova.plugins.GatewayPlugin.start();
	console.log("starting");
}

async function getServiceState() {
	let s = await cordova.plugins.GatewayPlugin.status();
	return s.running;
}

async function stopService() {
	updateServiceButton(STOPPING);
	let s = await cordova.plugins.GatewayPlugin.stop();
	console.log("stopping");
}




