const fs = require('fs');
const path = require('path');
const dgram = require('dgram');
const net = require('net');
const tls = require('tls');
const crypto = require('crypto');
const dns = require('dns');
const mkdirp = require('mkdirp').sync;
const rimraf = require('rimraf').sync;

const filesRead = {};

const dist = path.join(__dirname, 'dist');
rimraf(dist);

function readFile(filename) {
    var file = path.join(__dirname, filename);
    if (!fs.existsSync(file) || fs.statSync(file).isDirectory())
        return null;
    var ret = fs.readFileSync(file).toString();
    const relFile = path.relative(__dirname, filename);
    filesRead[relFile] = ret;

    const distFile = path.join(dist, relFile);
    mkdirp(path.dirname(distFile));
    fs.copyFileSync(file, distFile);
    return ret;
}

function evalScript(script, filename) {
    return eval(script);
}

const quackRequireFactory = evalScript(readFile('require.js'))

const quackRequire = quackRequireFactory(readFile, evalScript, {});

const defaultModules = {
    fs,
    dgram,
    net,
    tls,
    crypto,
    dns,
}

for (var module of Object.keys(defaultModules)) {
    quackRequire.cache[module] = { exports: defaultModules[module] }
}

const webTorrent = quackRequire('webtorrent');

console.log(webTorrent);
