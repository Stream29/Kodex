import { spawn } from "node:child_process";

let child;
let stopping = false;
let readyToKill = false;
function send(message) {
  if (process.connected) process.send(message, () => {});
}
function signalOwnGroup(signal) {
  try { process.kill(-process.pid, signal); }
  catch { send({ rowGroup: "error" }); process.exit(1); }
}
function stop() {
  if (stopping) return;
  stopping = true;
  setTimeout(() => {
    readyToKill = true;
    send({ rowGroup: "kill-ready" });
    setTimeout(() => signalOwnGroup("SIGKILL"), 100);
  }, 5000);
  signalOwnGroup("SIGTERM");
}
process.on("SIGTERM", stop);
process.on("SIGINT", stop);
process.on("disconnect", stop);
process.on("message", message => {
  if (message?.rowGroup === "stop") stop();
  else if (message?.rowGroup === "kill-now" && readyToKill) signalOwnGroup("SIGKILL");
  else if (message?.rowGroup === "forward" && child?.connected) child.send(message.value, () => {});
});
try {
  child = spawn(process.argv[2], process.argv.slice(3), {
    env: process.env, stdio: ["ignore", "inherit", "inherit", "ipc"],
  });
  child.on("message", value => send({ rowGroup: "forward", value }));
  child.once("error", () => { send({ rowGroup: "error" }); stop(); });
  child.once("exit", (code, signal) => { send({ rowGroup: "status", code, signal }); stop(); });
} catch {
  send({ rowGroup: "error" });
  stop();
}
