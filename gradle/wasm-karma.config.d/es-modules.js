const fs = require("fs");
const moduleFramework = "kodex-wasm-es-modules";
config.failOnEmptyTestSuite = true;
if (fs.existsSync(config.basePath + "/kotlin/composeResources")) {
    config.files.push({
        pattern: config.basePath + "/kotlin/composeResources/**/*",
        watched: false,
        included: false,
        served: true,
    });
    config.proxies = {
        ...config.proxies,
        "/composeResources/": "/base/kotlin/composeResources/",
    };
}
function loadWebpackAsModules(configuration) {
    const controller = configuration.__karmaWebpackController;
    controller.webpackOptions.output.publicPath = `/absolute${controller.outputPath}/`;
    for (const file of configuration.files) {
        const preprocessors = configuration.preprocessors[file.pattern] || [];
        if (preprocessors.includes("webpack") ||
            /[/\\]_karma_webpack_[^/\\]+[/\\](commons|runtime)\.js$/.test(file.pattern)) {
            file.type = "module";
        }
    }
}
loadWebpackAsModules.$inject = ["config"];
config.plugins.push({
    [`framework:${moduleFramework}`]: ["factory", loadWebpackAsModules]
});
config.frameworks.push(moduleFramework);
