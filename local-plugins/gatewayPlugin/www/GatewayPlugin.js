const exec = require('cordova/exec');

const SERVICE = 'GatewayPlugin';

function call(action, args) {
  return new Promise((resolve, reject) => {
    exec(resolve, reject, SERVICE, action, args || []);
  });
}

module.exports = {

  start() {
    return call('start');
  },

  stop() {
    return call('stop');
  },

  status() {
    return call('status');
  },

  checkSmsPermission() {
    return call('checkSmsPermission');
  },

  requestSmsPermission() {
    return call('requestSmsPermission');
  },

  checkNotificationPermission() {
    return call('checkNotificationPermission');
  },

  requestNotificationPermission() {
    return call('requestNotificationPermission');
  },

  checkOverlayPermission() {
    return call('checkOverlayPermission');
  },

  requestOverlayPermission() {
    return call('requestOverlayPermission');
  },

  checkBatteryOptimization() {
    return call('checkBatteryOptimization');
  },

  requestBatteryOptimization() {
    return call('requestBatteryOptimization');
  }

};
