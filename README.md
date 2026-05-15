# About IHS Gateway

## Purpose
The IHS Gateway has been designed specifically to reduce points of failure on getting crucial notifications out from the station to real world recipients.

## How It Works
![Schematic](/www/img/ihs.png)

## Permissions
[INTERNET]

[ACCESS_NETWORK_STATE]

[SEND_SMS]

[FOREGROUND_SERVICE]

[FOREGROUND_SERVICE_LOCATION]

[FOREGROUND_SERVICE_SPECIAL_USE]

[POST_NOTIFICATIONS]

[RECEIVE_BOOT_COMPLETED]

[SYSTEM_ALERT_WINDOW]

[REQUEST_IGNORE_BATTERY_OPTIMIZATIONS]

## Extra Android Settings</h2>
Settings->Apps->IHS Gateway->Manage app if unused - off

Settings->Battery->Battery protection - on and set to 80%

## Credits
Apache Cordova &trade;

Onsen UI

Cordova Plugins: **[gatewayPlugin 1.0.0]** (in local-plugins)
		
## Developer
All concepts, design and coding by Derek Dikstaal

Waiwera Software 2024

**[derek.dikstaal@gmail.com]**

## Version
1.0.0

# Reminders (to self)

## Port forwarding of current SMTP Server
netsh interface portproxy add v4tov4 listenport=25 listenaddress=0.0.0.0 connectport=2525 connectaddress=192.168.4.24
netsh interface portproxy delete v4tov4 listenport=25 listenaddress=0.0.0.0

## Cordova Build
cordova platform add android

cordiva plugin add .\local-plugins\gatewayPlugin

