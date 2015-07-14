@echo off 
cd C:\Work\Home\CO2\java\
set LIB=libs/commons-lang3-3.2.1.jar;libs/libusb4java-1.2.0-windows-x86.jar;libs/usb-api-1.0.2.jar;libs/usb4java-1.2.0.jar;bin;resources
start "co2mon" javaw -classpath %LIB% Co2mon -a 300