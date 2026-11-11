// Functional checks (relative CSS/image/JS, quiz interaction) and escape attempts that must be blocked (ADR-0003).
function request(url) {
  return new Promise(function (resolve) {
    try {
      var xhr = new XMLHttpRequest();
      xhr.open('GET', url, true);
      xhr.onload = function () { resolve({ status: xhr.status, body: xhr.responseText }); };
      xhr.onerror = function () { resolve({ status: 'error' }); };
      xhr.send();
    } catch (e) { resolve({ status: 'exception' }); }
  });
}
function expectBlocked(name, url) {
  return request(url).then(function (r) { return name + '=' + (r.status === 200 ? 'LEAK' : 'blocked(' + r.status + ')'); });
}
window.addEventListener('load', function () {
  var lines = ['javascript=ok'];
  lines.push('relativeCss=' + (getComputedStyle(document.querySelector('h1')).color === 'rgb(232, 93, 4)' ? 'ok' : 'FAIL'));
  lines.push('relativeImage=' + (document.querySelector('img').naturalWidth > 0 ? 'ok' : 'FAIL'));
  var option = document.querySelector('[data-correct]');
  option.click();
  lines.push('quizInteraction=' + (option.classList.contains('correct') ? 'ok' : 'FAIL'));
  Promise.all([
    request('../02%20-%20Code%20%26%20Slides%20Download/external-links.txt').then(function (r) {
      return 'sameCourseFile=' + (r.status === 200 ? 'ok' : 'FAIL(' + r.status + ')');
    }),
    expectBlocked('fileUrlRead', 'file:///etc/passwd'),
    expectBlocked('encodedTraversal', './%2e%2e/%2e%2e/Other%20Course/private.txt'),
    expectBlocked('siblingCourse', '../../Other%20Course/private.txt'),
    expectBlocked('absoluteEscape', location.pathname.split('/').slice(0, 2).join('/') + '//etc/passwd')
  ]).then(function (more) {
    var results = document.getElementById('results');
    results.innerText = lines.concat(more).join('\n');
    results.dataset.done = 'true';
  });
});
