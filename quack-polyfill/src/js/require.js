(function(readFile, evalScript, global) {

function readJsonFile(filename) {
    const string = readFile(filename);
    if (!string)
        return;
    return JSON.parse(string);
}

function requireFactory(callingScript) {
    const require = function require(moduleName) {
        const currentPath = path.dirname(callingScript);
        const isPath = moduleName.startsWith('/') || moduleName.startsWith('.') || moduleName.startsWith('\\');
    
        // require cache can be set externally, so do a straight name check here.
        if (requireCache[moduleName])
            return requireCache[moduleName].exports;

        if (builtins[moduleName])
            return requireBuiltin(moduleName).exports;

        return requireFind(moduleName, currentPath, isPath).exports;
    }

    require.cache = requireCache;
    return require;
}

function requireLoadInternal(scriptString, exports, __require, newModule, filename, dirname, process) {
    if (!scriptString)
        return;
    if (filename.endsWith('.json')) {
        newModule.exports = JSON.parse(scriptString);
        return newModule;
    }
    // const moduleFunc = new Function('exports', 'require', 'module', '__filename', '__dirname', scriptString);
    const wrapped = `(function(exports, require, module, __filename, __dirname, process){${scriptString}})`;
    const moduleFunc = evalScript(wrapped, filename);
    moduleFunc(exports, __require, newModule, filename, dirname, process);
    return newModule;
}

function requireLoad(scriptString, filename, module) {
    return requireLoadInternal(scriptString, module.exports, requireFactory(filename), module, filename, path.dirname(filename), process);
}

const builtins = {
    inherits: {
        name: 'inherits',
        main: './inherits_browser.js',
    },
    zlib: 'browserify-zlib',
    assert: 'assert',
    url: 'url',
    'builtin-status-codes': {
        name: 'builtin-status-codes',
        main: './browser.js',
    },
    process: {
        name: 'process',
        main: './browser.js',
    },
    os: "os-browserify",
    path: "path-browserify",
    buffer: "buffer",
    https: "https-browserify",
    stream: "stream-browserify",
    http: "stream-http",
    events: 'events',
    util: 'util',
};

function requireLoadSingleFile(fullname) {
    const found = requireCache[fullname];
    if (found)
        return found;
    const fileString = readFile(fullname)
    if (!fileString)
        return;
    const ret = requireLoad(fileString, fullname, createModule(fullname));
    return ret;
}
function appendScriptExtension(file) {
    return `${file}.js`;
}

function requireLoadFile(fullname) {
    let ret = requireLoadSingleFile(appendScriptExtension(fullname));
    if (ret)
        return ret;
    return requireLoadSingleFile(fullname);
}

function createModule(fullname) {
    const module = {
        exports: {}
    };
    requireCache[fullname] = module;
    if (fullname == '/Volumes/Dev/quackfill/quack-polyfill/node/node_modules/webtorrent/package.json')
        console.log(`creating module ${fullname}`);
    return module;
}

function requireLoadPackage(fullpath, main) {
    const found = requireCache[fullpath];
    if (found)
        return found;
    const packageJson = readJsonFile(path.join(fullpath, 'package.json'));
    if (!packageJson)
        return;
    main = path.join(fullpath, main || packageJson.main || 'index.js');
    let fileString = readFile(main);
    if (!fileString)
        fileString = readFile(appendScriptExtension(main));
    if (!fileString) {
        main = path.join(main, 'index.js')
        fileString = readFile(main);
    }
    const ret = requireLoad(fileString, main, createModule(fullpath));
    return ret;
}

function requireFind(name, directory, isPath) {
    let parent = directory;

    do {
        directory = parent;
        let fullname = path.join(directory, name);
        let ret = requireLoadFile(fullname);
        if (ret || isPath)
            return ret;
    
        let fullpath = path.join(directory, 'node_modules', name);
        ret = requireLoadPackage(fullpath);
        if (ret)
            return ret;

        parent = path.dirname(directory);
    }
    while (!isPath && directory !== parent);
    throw new Error(`unable to load ${name}`);
}

function requireBuiltin(moduleName) {
    const modulePath = path.join('./node_modules', builtins[moduleName].name || builtins[moduleName]);
    var ret = requireLoadPackage(modulePath, builtins[moduleName].main);
    requireCache[moduleName] = ret;
    return ret;
}

const requireCache = {};
// require  imeplementation needs the path module, but can't require without path being ready.
// chicken egg problem.
function requirePath() {
    const pathDir = `./node_modules/path-browserify`;
    const pathPath = `${pathDir}/index.js`;
    const pathScript = readFile(pathPath);
    const module = createModule(pathPath);
    const ret = requireLoadInternal(pathScript, module.exports, null, module, pathPath, pathDir);
    console.log(pathPath)
    if (!ret)
        throw new Error('unable to load path module');
    requireCache[`${pathDir}`] = ret;
    requireCache['path'] = ret;
    return ret.exports;
}

const path = requirePath();
global.global = global;

const require = requireFactory('./require.js');

let process = {};
process = require('process');
// for debug module
process.type = 'renderer';
// process.env.DEBUG = '*';
global.process = process;
global.Buffer = require('buffer').Buffer;

const oldToString = Object.prototype.toString;
Object.prototype.toString = function() {
    if (this === process)
        return '[object process]';
    return oldToString.apply(this);
}

global.location = {
    protocol: 'https:'
}

return require;
})