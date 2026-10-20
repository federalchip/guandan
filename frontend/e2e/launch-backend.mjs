import { spawn, spawnSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const backend = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../backend')
const mvnw = path.join(backend, process.platform === 'win32' ? 'mvnw.cmd' : 'mvnw')
const classpathFile = path.join(backend, 'target/e2e-classpath.txt')
const restartFile = path.join(backend, 'target/e2e-restart.signal')
const prepare = spawnSync(mvnw, [
  '-q', 'test-compile', 'dependency:build-classpath', '-Dmdep.outputFile=target/e2e-classpath.txt',
], { cwd: backend, shell: process.platform === 'win32', stdio: 'inherit' })
if (prepare.status !== 0) process.exit(prepare.status ?? 1)

const classpath = [
  path.join(backend, 'target/test-classes'),
  path.join(backend, 'target/classes'),
  readFileSync(classpathFile, 'utf8').trim(),
].join(path.delimiter)

let stopping = false
let restarting = false
let seenSignal = existsSync(restartFile) ? readFileSync(restartFile, 'utf8') : ''
let server

function start() {
  server = spawn('java', [
    '-cp', classpath, 'com.guandan.service.E2eApplication',
    '--spring.profiles.active=e2e', '--server.address=127.0.0.1', '--server.port=8001',
  ], { cwd: backend, stdio: 'inherit' })
  server.on('exit', (code) => {
    if (stopping) return
    if (restarting) {
      restarting = false
      start()
    } else {
      process.exit(code ?? 1)
    }
  })
}

start()
setInterval(() => {
  if (stopping || restarting || !existsSync(restartFile)) return
  const signal = readFileSync(restartFile, 'utf8')
  if (!/^[0-9a-f-]{36}$/.test(signal) || signal === seenSignal) return
  seenSignal = signal
  restarting = true
  server.kill()
}, 100)

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    stopping = true
    server.kill()
    process.exit(0)
  })
}
