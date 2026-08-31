const path = require('path');
const {CleanWebpackPlugin} = require('clean-webpack-plugin');
const CopyWebpackPlugin = require('copy-webpack-plugin');
const ModuleFederationPlugin = require('webpack/lib/container/ModuleFederationPlugin');
const moonstone = require('@jahia/moonstone/dist/rulesconfig-wp');
const getModuleFederationConfig = require('@jahia/webpack-config/getModuleFederationConfig');
const packageJson = require('./package.json');

module.exports = (env, argv) => {
    const config = {
        entry: {
            main: path.resolve(__dirname, 'src/javascript/index')
        },
        output: {
            path: path.resolve(__dirname, 'src/main/resources/javascript/apps/'),
            // A fixed name, not a hash. The output directory is wiped and rebuilt on every package,
            // so a hashed main bundle only accumulates orphans nobody deletes.
            filename: 'content-revision-history.bundle.js',
            chunkFilename: '[name].jahia.[chunkhash:6].js'
        },
        resolve: {
            mainFields: ['module', 'main'],
            extensions: ['.mjs', '.js', '.jsx', '.json'],
            fallback: {url: false}
        },
        module: {
            rules: [
                ...moonstone,
                {
                    test: /\.m?js$/,
                    type: 'javascript/auto'
                },
                {
                    // This module's own stylesheets, as CSS modules. moonstone's rules above handle
                    // .css too, but they are scoped with include: [its own dist], so the two never
                    // compete for the same file.
                    test: /\.css$/,
                    include: [path.join(__dirname, 'src')],
                    use: [
                        'style-loader',
                        {loader: 'css-loader', options: {modules: {mode: 'local'}}}
                    ]
                },
                {
                    test: /\.jsx?$/,
                    include: [path.join(__dirname, 'src')],
                    use: {
                        loader: 'babel-loader',
                        options: {
                            presets: [
                                ['@babel/preset-env', {
                                    modules: false,
                                    targets: {chrome: '60', edge: '44', firefox: '54', safari: '12'}
                                }],
                                '@babel/preset-react'
                            ],
                            plugins: ['@babel/plugin-syntax-dynamic-import']
                        }
                    }
                }
            ]
        },
        plugins: [
            // getModuleFederationConfig hardcodes exposes './init' -> './src/javascript/init', which
            // is why the sources live in src/javascript and not under src/main. It also marks every
            // shared dependency import:false, so react and moonstone are NOT bundled: the versions
            // in package.json widen the accepted range, and the app shell's own copies are what
            // actually execute at runtime.
            new ModuleFederationPlugin(getModuleFederationConfig(packageJson, {
                library: {type: 'assign', name: 'appShell.remotes.contentRevisionHistory'}
            })),
            new CleanWebpackPlugin({verbose: false}),
            // The app shell discovers a module's remotes by fetching javascript/apps/package.json,
            // so the manifest has to sit next to remoteEntry.js, not just at the module root.
            new CopyWebpackPlugin({patterns: [{from: './package.json', to: ''}]})
        ],
        mode: 'development'
    };

    config.devtool = (argv.mode === 'production') ? 'source-map' : 'eval-source-map';
    return config;
};
