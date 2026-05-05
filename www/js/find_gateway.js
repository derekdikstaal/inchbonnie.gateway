const os = require("os");
const http = require("http");
const { execSync } = require("child_process");

const PORT = Number(process.argv[2] || 8080);
const TIMEOUT_MS = Number(process.argv[3] || 900);
const CONCURRENCY = Number(process.argv[4] || 80);
const RETRIES = 2;

const PATHS = ["/status", "/", "/admin"];
const MATCH_TEXT = ["running", "gateway", "Gateway", "status"];

function isAllowedIPv4(ip) {
  const p = ip.split(".").map(Number);
  return (
    p[0] === 10 ||
    (p[0] === 192 && p[1] === 168) ||
    (p[0] === 172 && p[1] >= 16 && p[1] <= 31) ||
    p[0] === 127
  );
}

function subnet24(ip) {
  const p = ip.split(".");
  return `${p[0]}.${p[1]}.${p[2]}`;
}

function getLocalTargets() {
  const interfaces = os.networkInterfaces();
  const subnets = new Set();
  const directHosts = new Set(["127.0.0.1", "localhost"]);

  for (const name of Object.keys(interfaces)) {
    for (const net of interfaces[name]) {
      if (net.family !== "IPv4") continue;
      if (!isAllowedIPv4(net.address)) continue;

      if (net.address.startsWith("127.")) {
        directHosts.add(net.address);
        continue;
      }

      const subnet = subnet24(net.address);
      subnets.add(subnet);

      // Common router/DHCP/gateway-ish addresses. These are checked early.
      directHosts.add(`${subnet}.1`);
      directHosts.add(`${subnet}.2`);
      directHosts.add(`${subnet}.254`);
      directHosts.add(net.address);
    }
  }

  // Add ARP cache entries. This makes discovery much faster/more reliable after
  // the phone has talked to the PC before, even if scanning misses it once.
  for (const ip of getArpHosts()) {
    if (isAllowedIPv4(ip)) directHosts.add(ip);
  }

  return {
    subnets: [...subnets],
    directHosts: [...directHosts]
  };
}

function getArpHosts() {
  try {
    const output = execSync("arp -a", { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] });
    const matches = output.match(/\b(?:\d{1,3}\.){3}\d{1,3}\b/g) || [];
    return [...new Set(matches.filter(ip => ip !== "255.255.255.255"))];
  } catch {
    return [];
  }
}

function requestPath(ip, path) {
  return new Promise((resolve) => {
    const req = http.get(
      {
        host: ip,
        port: PORT,
        path,
        timeout: TIMEOUT_MS,
        headers: { "Connection": "close" }
      },
      (res) => {
        let data = "";
        res.setEncoding("utf8");
        res.on("data", chunk => {
          data += chunk;
          if (data.length > 4096) req.destroy();
        });
        res.on("end", () => {
          const matched = res.statusCode === 200 && MATCH_TEXT.some(t => data.includes(t));
          resolve({ ip, path, found: matched, statusCode: res.statusCode, body: data });
        });
      }
    );

    req.on("timeout", () => {
      req.destroy();
      resolve({ ip, path, found: false });
    });

    req.on("error", () => resolve({ ip, path, found: false }));
  });
}

async function checkHost(ip) {
  for (let attempt = 1; attempt <= RETRIES; attempt++) {
    for (const path of PATHS) {
      const result = await requestPath(ip, path);
      if (result.found) return { ...result, attempt };
    }
  }

  return { ip, found: false };
}

async function runLimited(items, worker, limit) {
  const results = [];
  let index = 0;

  async function runner() {
    while (index < items.length) {
      const current = items[index++];
      results.push(await worker(current));
    }
  }

  const runners = [];
  const count = Math.min(limit, items.length);
  for (let i = 0; i < count; i++) runners.push(runner());
  await Promise.all(runners);
  return results;
}

async function scanHosts(hosts, label) {
  const unique = [...new Set(hosts)];
  if (unique.length === 0) return [];

  console.log(`${label}: checking ${unique.length} host(s) on port ${PORT}`);
  const results = await runLimited(unique, checkHost, CONCURRENCY);
  return results.filter(r => r.found);
}

async function main() {
  const { subnets, directHosts } = getLocalTargets();

  console.log(`Gateway scanner: port=${PORT}, timeout=${TIMEOUT_MS}ms, concurrency=${CONCURRENCY}, retries=${RETRIES}`);
  console.log(`Subnets: ${subnets.length ? subnets.join(", ") : "none"}`);

  let found = [];

  // Fast pass: localhost, common gateway addresses and ARP cache.
  found = found.concat(await scanHosts(directHosts, "Fast pass"));

  // Full /24 scan for every active private interface.
  for (const subnet of subnets) {
    const hosts = [];
    for (let i = 1; i <= 254; i++) hosts.push(`${subnet}.${i}`);
    found = found.concat(await scanHosts(hosts, `Scanning ${subnet}.1-254`));
  }

  const byIp = new Map();
  for (const r of found) byIp.set(r.ip, r);
  found = [...byIp.values()];

  if (found.length === 0) {
    console.log("No gateway found.");
    console.log("Tip: make sure the phone and PC are on the same WiFi/VLAN, the service is started, and Windows firewall/VPN is not isolating the network.");
    process.exitCode = 1;
    return;
  }

  console.log("\nGateway found:");
  for (const r of found) {
    console.log(`http://${r.ip}:${PORT}  (matched ${r.path}, attempt ${r.attempt || 1})`);
    if (r.body) console.log(r.body.slice(0, 500).trim());
    console.log("");
  }
}

main().catch(err => {
  console.error("Scanner failed:", err);
  process.exit(1);
});
