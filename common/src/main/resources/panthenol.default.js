const SKIN_BASE = 'https://auth.tlauncher.org/';

const toAbsoluteURL = (url) => {
    if (!url) return null;
    if (/^https?:\/\//i.test(url)) return url;

    return SKIN_BASE + String(url).replace(/^\/+/, '');
};

const load = (params) => {
    // Single request: object form. For several URLs use an array — they run in parallel.
    const response = http.get({
        url: `https://auth.tlauncher.org/skin/v1/profile/texture/login/${encodeURIComponent(params.name)}`,
        mode: 'json'
    });

    if (!response || response.status < 200 || response.status >= 300 || !response.body) {
        return null;
    }

    const data = response.body;
    const result = {};

    if (data.SKIN && data.SKIN.url) {
        result.skin = {
            url: toAbsoluteURL(data.SKIN.url),
            model: data.SKIN.metadata && data.SKIN.metadata.model
        };
    }

    if (data.CAPE && data.CAPE.url) {
        result.cape = {
            url: toAbsoluteURL(data.CAPE.url)
        };
    }

    if (!result.skin && !result.cape) {
        return null;
    }

    return result;
};

const config = {
    // true  = keep skins until client restart
    // false = refetch when you join a world / when a player joins
    cache: false
};
