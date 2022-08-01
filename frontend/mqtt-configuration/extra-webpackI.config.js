const path = require('path');
const CopyPlugin = require('copy-webpack-plugin');

module.exports = function config(env) {
    return {
        output: {
            path: path.join(__dirname, './dist/apps/mqtt-configuration')
        },
        plugins: [
            new CopyPlugin({
                patterns: [
                    { from: 'node_modules/monaco-editor/min/vs', to:  path.join(__dirname, './dist/apps/mqtt-configuration/assets/monaco-editor/min/vs') },
                ],
            })
        ],
    }
};