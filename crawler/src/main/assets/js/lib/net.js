let req = (url, options) => http(url, Object.assign({
    async: false
}, options));

// quickjs-kt 下 JS 函数值不能跨 JNI 传递 (complete 回调方案不可用),
// 异步请求改由 _httpAsync asyncFunction 绑定直接返回 Promise.
function http(url, options = {}) {
    if (options?.async === false) return _http(url, options)
    return _httpAsync(url, options).catch(err => {
        console.error(String(err))
        return {
            ok: false,
            status: 500,
            url
        }
    })
};
