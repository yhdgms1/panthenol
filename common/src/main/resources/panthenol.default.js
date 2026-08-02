const API_BASE = 'https://auth.tlauncher.org';
const PROFILE_URL = API_BASE + '/skin/v1/profile/texture/login/';

const withBaseURL = function (path) {
    if (!path) return null;
    if (/^https?:\/\//i.test(path)) return path;
    return API_BASE + '/' + String(path).replace(/^\/+/, '');
};

const isSuccess = function (response) {
    return response &&
        response.status >= 200 &&
        response.status < 300;
};

/**
 * `texture` is either:
 *   - http(s) URL string  → Java downloads the PNG
 *   - Symbol handle       → from http.get / fs.readFile with mode: 'texture'
 *
 * Examples:
 *   skin: { texture: 'https://…/a.png', model: 'slim' }
 *   skin: { texture: fs.readFile({ path: 'panthenol/a.png' }).body, model: 'slim' }
 *   cape: handle   // shorthand when no model
 */
const load = function (params) {
    const profile = http.get({
        url: PROFILE_URL + encodeURIComponent(params.name),
        mode: 'json'
    });

    if (!isSuccess(profile) || !profile.body) {
        return null;
    }

    const SKIN = profile.body.SKIN;
    const CAPE = profile.body.CAPE;
    const result = {};

    if (SKIN && SKIN.url) {
        result.skin = {
            texture: withBaseURL(SKIN.url),
            model: SKIN.metadata ? SKIN.metadata.model : undefined
        };
    }

    if (CAPE && CAPE.url) {
        result.cape = {
            texture: withBaseURL(CAPE.url)
        };
    }

    return result.skin || result.cape ? result : null;
};

const config = {
    // true  = keep skins until client restart
    // false = refetch when you join a world / when a player joins
    cache: false
};
