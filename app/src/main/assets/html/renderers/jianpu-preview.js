/* Offline numbered notation. Fit systems to the available width at a fixed reading size. */
(function () {
    'use strict';
    const svgNs = 'http://www.w3.org/2000/svg';
    const tokenPattern = /\|:|:\||\|\||\|\]|\||[♭b♯#♮n]?[0-7]['’,\x60]*[_=.\-]*|[-–—]+/g;
    const barPattern = /[|]/;
    const metadataPattern = /^(?:\/key\(|(?:title|subtitle|t|bpm|tempo|composer|lyricist)\s*[:=]|ｂｐｍ\s+|1\s*=\s*[A-G](?:[#♯b♭])?(?=\s|$)|\d+\/\d+\s*$)/i;
    const maxTokens = 1600;
    const maxLineLength = 256;

    function parse(source) {
        const score = { metadata: [], phrases: [] };
        let count = 0;
        for (const line of source.split(/\r\n?|\n/)) {
            const text = line.trim().replaceAll('｜', '|').replaceAll('，', ',').replaceAll('＃', '#');
            if (!text || text.startsWith('%')) continue;
            const lyric = /^(?:L|w|lyrics?|歌词)\s*:\s*(.*)$/i.exec(text);
            if (lyric) {
                const phrase = score.phrases[score.phrases.length - 1];
                if (phrase && phrase.lyrics.length < 4) phrase.lyrics.push(lyric[1].slice(0, maxLineLength));
                continue;
            }
            const harmony = /^(?:H|harmony|和声)\s*:\s*(.*)$/i.exec(text);
            if (harmony) {
                const phrase = score.phrases[score.phrases.length - 1];
                if (!phrase) throw new Error('和声标注须位于旋律行之后');
                const labels = harmony[1].replace(/^\s*\||\|\s*$/g, '').split('|').map(s => s.trim());
                if (labels.length !== phrase.measures.length || labels.some(s => s.length > 24)) throw new Error('H: 每个小节需对应一个和声标注，单项最多 24 字符');
                labels.forEach((label, i) => {
                    const item = phrase.measures[i].find(item => !item.bar);
                    if(item && label) { item.chord = label; item.width = Math.max(item.width, label.length * 8 + 8); }
                });
                continue;
            }
            if (metadataPattern.test(text)) {
                if (score.metadata.length < 8) score.metadata.push(text.slice(0, maxLineLength));
                continue;
            }
            const measures = [];
            let measure = [];
            // Strip trailing comments. Digits in unrelated prose must not turn into notes.
            const melody = text.split('%', 1)[0];
            const tokens = melody.match(tokenPattern);
            if (!tokens || !tokens.some(token => /^[♭b♯#♮n]?[0-7]/.test(token))
                || melody.replace(tokenPattern, '').replace(/[\s()[\]{}~]/g, '')) continue;
            for (const token of tokens) {
                if (++count > maxTokens) throw new Error('简谱超过 1600 个音符或分隔符，请分段显示');
                const bar = barPattern.test(token);
                measure.push({ token, bar, width: bar ? 16 : 30 });
                if (bar && measure.length > 1 && token !== '|:') {
                    measures.push(measure);
                    measure = [];
                }
            }
            if (measure.length) measures.push(measure);
            score.phrases.push({ measures, lyrics: [] });
        }
        if (!score.phrases.length) throw new Error('未找到简谱音符，请检查源码格式');
        return score;
    }

    function tag(kind, attrs, content) {
        const node = document.createElementNS(svgNs, kind);
        for (const [key, value] of Object.entries(attrs)) node.setAttribute(key, value);
        if (content !== undefined) node.textContent = content;
        return node;
    }

    function text(value, x, y, size, anchor = 'middle') {
        return tag('text', { x, y, 'font-size': size, 'font-family': 'sans-serif',
            'text-anchor': anchor, fill: 'currentColor' }, value);
    }

    function line(x1, y1, x2, y2, width = 1) {
        return tag('line', { x1, y1, x2, y2, 'stroke-width': width, stroke: 'currentColor' });
    }

    function dot(x, y) {
        return tag('circle', { cx: x, cy: y, r: 1.8, fill: 'currentColor' });
    }

    function drawToken(parent, item, x, y) {
        const token = item.token;
        if(item.chord) parent.append(text(item.chord, x + 2, y - 38, 14, 'start'));
        if (item.bar) {
            parent.append(line(x + 8, y - 20, x + 8, y + 7, token === '|]' ? 2.4 : 1.2));
            if (token !== '|') parent.append(line(x + 11, y - 20, x + 11, y + 7));
            if (token.includes(':')) {
                const dx = token[0] === ':' ? 3 : 14;
                parent.append(dot(x + dx, y - 10), dot(x + dx, y - 3));
            }
            return;
        }
        if (/^[-–—]/.test(token)) {
            parent.append(text('–', x + 15, y, 23));
            return;
        }
        const [, accidental, digit, octave, duration] = /^([♭b♯#♮n]?)([0-7])(['’,\x60]*)(.*)$/.exec(token);
        const center = x + 15;
        parent.append(text(digit, center, y, 23));
        if (accidental) {
            const symbol = { '#': '♯', b: '♭', n: '♮' }[accidental] || accidental;
            parent.append(text(symbol, center - 12, y - 8, 13));
        }
        const beams = Math.min(3, [...duration].reduce((sum, c) => sum + (c === '=' ? 2 : c === '_' ? 1 : 0), 0));
        for (let i = 0; i < beams; i++) parent.append(line(center - 7, y + 4 + 3 * i, center + 7, y + 4 + 3 * i, 1.2));
        let low = 0, high = 0;
        for (const symbol of octave.slice(0, 3)) {
            if (symbol === ',') parent.append(dot(center, y + 8 + beams * 3 + 5 * low++));
            else parent.append(dot(center, y - 23 - 5 * high++));
        }
        let offset = 0;
        for (const symbol of duration.slice(0, 6)) {
            if (symbol === '.') parent.append(dot(center + 10 + offset++ * 4, y - 6));
            if (symbol === '-') parent.append(line(center + 8 + offset++ * 4, y - 6, center + 12 + (offset - 1) * 4, y - 6));
        }
    }

    function render(root, score) {
        const width = Math.max(100, root.clientWidth - 16);
        const svg = tag('svg', { width, role: 'img', 'aria-label': '简谱' });
        let y = 8;
        function labelRows(value, fontSize) {
            // Wrap labels on phones and keep lyric lines readable even with unspaced CJK.
            const chars = Array.from(value);
            const perLine = Math.max(1, Math.floor((width - 16) / fontSize));
            for (let offset = 0; offset < chars.length; offset += perLine) {
                y += fontSize + 4;
                svg.append(text(chars.slice(offset, offset + perLine).join(''), 8, y, fontSize, 'start'));
            }
        }
        score.metadata.forEach((value, index) => labelRows(value, index === 0 ? 16 : 13));
        for (const phrase of score.phrases) {
            let row = [], room = width - 16;
            function finishRow() {
                if (!row.length) return;
                const system = tag('g', { 'data-score-system': '' });
                y += row.some(item => item.chord) ? 65 : 41;
                let x = 8;
                for (const item of row) {
                    drawToken(system, item, x, y);
                    x += item.width;
                }
                svg.append(system);
                y += 29;
                row = [];
                room = width - 16;
            }
            for (const measure of phrase.measures) {
                const span = measure.reduce((sum, item) => sum + item.width, 0);
                if (span > room && row.length) finishRow();
                for (const item of measure) {
                    if (item.width > room) finishRow();
                    row.push(item);
                    room -= item.width;
                }
            }
            finishRow();
            phrase.lyrics.forEach(value => labelRows(value, 14));
            if (phrase.lyrics.length) y += 8;
        }
        svg.setAttribute('height', y + 8);
        svg.setAttribute('viewBox', '0 0 ' + width + ' ' + (y + 8));
        root.replaceChildren(svg);
        if(window.rikkaScorePages) window.rikkaScorePages(root, window.rikkaScoreSystems(svg), false);
    }

    window.rikkaRenderJianpu = function (root, source, onError) {
        const score = parse(source);
        render(root, score);
        window.rikkaReflowScore(root, () => render(root, score), onError);
    };
})();
