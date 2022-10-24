const path = require('path');
const CopyPlugin = require('copy-webpack-plugin');

module.exports = function config(env) {
    return {
        output: {
            path: path.join(__dirname, './dist/apps/mqtt-mapping')
        },
        plugins: [
            new CopyPlugin({
                patterns: [
                    { from: 'node_modules/jsoneditor/dist/img/jsoneditor-icons.svg', to:  path.join(__dirname, './dist/apps/mqtt-mapping/assets/img') },
                ],
            }),
        ],
    }
}; 