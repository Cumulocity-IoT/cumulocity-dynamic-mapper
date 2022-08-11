const path = require('path');
const CopyPlugin = require('copy-webpack-plugin');

module.exports = function config(env) {
    return {
        output: {
            path: path.join(__dirname, './dist/apps/mqtt-configuration/mqtt-mapping')
        },
        plugins: [
            new CopyPlugin({
                patterns: [
                    { from: 'node_modules/jsoneditor/dist/jsoneditor.min.css', to:  path.join(__dirname, './jsoneditor.min.css') },
                ],
            })
        ],
    }
};
