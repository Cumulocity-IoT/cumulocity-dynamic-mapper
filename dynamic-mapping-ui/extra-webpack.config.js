const path = require("path");
module.exports = {
  module: {
    rules: [
      {
        test: [/.css$/],
        include: path.resolve(__dirname, 'src/shared/styles'),
        use: [
            "style-loader",
            "css-loader",
        ],
      },
    ],
  },
};
