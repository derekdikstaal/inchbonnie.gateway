"# inchbonnie.gateway" 

netsh interface portproxy add v4tov4 listenport=25 listenaddress=0.0.0.0 connectport=2525 connectaddress=192.168.4.24
netsh interface portproxy delete v4tov4 listenport=25 listenaddress=0.0.0.0


