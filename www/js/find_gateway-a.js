const os = require("os");
const http = require("http");

const PORT = 8080;
const PATH = "/status";
const TIMEOUT_MS = 500;

function isAllowedIPv4(ip) {
  const parts = ip.split(".").map(Number);

  return (
    parts[0] === 10 ||
    (parts[0] === 192 && parts[1] === 168) ||
    (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) ||
    parts[0] === 127
  );
}

function getLocalTargets() {
  const interfaces = os.networkInterfaces();
  const subnets = new Set();
  const directHosts = new Set(["127.0.0.1"]);

  for (const name of Object.keys(interfaces)) {
    for (const net of interfaces[name]) {
      if (net.family !== "IPv4") continue;
      if (!isAllowedIPv4(net.address)) continue;

      if (net.address.startsWith("127.")) {
        directHosts.add(net.address);
        continue;
      }

      const parts = net.address.split(".");
      subnets.add(`${parts[0]}.${parts[1]}.${parts[2]}`);
    }
  }

  return {
    subnets: [...subnets],
    directHosts: [...directHosts]
  };
}

function checkHost(ip) {
  return new Promise((resolve) => {
    const req = http.get(
      {
        host: ip,
        port: PORT,
        path: PATH,
        timeout: TIMEOUT_MS
      },
      (res) => {
        let data = "";

        res.on("data", chunk => data += chunk);
        res.on("end", () => {
          if (res.statusCode === 200 && data.includes("running")) {
            resolve({ ip, found: true, body: data });
          } else {
            resolve({ ip, found: false });
          }
        });
      }
    );

    req.on("timeout", () => {
      req.destroy();
      resolve({ ip, found: false });
    });

    req.on("error", () => {
      resolve({ ip, found: false });
    });
  });
}

async function main() {
  const { subnets, directHosts } = getLocalTargets();

  let found = [];

  for (const host of directHosts) {
    console.log(`Checking ${host}:${PORT}${PATH}`);
    const result = await checkHost(host);
    if (result.found) found.push(result);
  }

  for (const subnet of subnets) {
    console.log(`Scanning ${subnet}.1-254:${PORT}${PATH}`);

    const checks = [];
    for (let i = 1; i <= 254; i++) {
      checks.push(checkHost(`${subnet}.${i}`));
    }

    const results = await Promise.all(checks);
    found = found.concat(results.filter(r => r.found));
  }

  if (found.length === 0) {
    console.log("No gateway found.");
    return;
  }

  console.log("\nGateway found:");
  for (const result of found) {
    console.log(`http://${result.ip}:${PORT}`);
    console.log(result.body);
  }
}

main();