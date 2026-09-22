<template>
  <section class="value-proposition" aria-labelledby="value-proposition-title">
    <div class="glass-panel value-proposition__card">
      <p class="value-proposition__eyebrow">THE PROMPTING ADVANTAGE</p>
      <h2 id="value-proposition-title">把“想清楚”，变成“说清楚”。</h2>

      <blockquote>
        <p>
          AI 时代真正拉开差距的，不是谁用了更贵的模型，是谁更清楚自己要啥、更会把这份清楚说出来。
        </p>
      </blockquote>

      <div class="value-proposition__details">
        <p>
          真正有用的提问，是把脑子里模糊的想法，变成别人能够理解、执行和检验的任务。
        </p>
        <p>提问这件事，正在变成和写作、沟通一样重要的基本功。</p>
      </div>
    </div>
  </section>
</template>

<style scoped>
.value-proposition {
  padding: 24px;
}

.value-proposition__card {
  position: relative;
  max-width: 920px;
  margin: 0 auto;
  padding: clamp(32px, 6vw, 64px);
  overflow: hidden;
}

.value-proposition__card::before {
  position: absolute;
  top: -88px;
  right: -48px;
  width: 240px;
  height: 240px;
  border-radius: 50%;
  content: '';
  background: color-mix(in srgb, var(--accent) 15%, transparent);
  filter: blur(16px);
  pointer-events: none;
}

.value-proposition__eyebrow {
  position: relative;
  margin: 0 0 14px;
  color: var(--accent);
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.8px;
}

h2 {
  position: relative;
  max-width: 620px;
  margin: 0;
  color: var(--text-primary);
  font-size: clamp(28px, 4.2vw, 44px);
  line-height: 1.22;
  letter-spacing: -0.02em;
}

blockquote {
  position: relative;
  max-width: 740px;
  margin: 32px 0 0;
  padding-left: 20px;
  border-left: 3px solid var(--accent);
}

blockquote p {
  margin: 0;
  color: var(--text-primary);
  font-size: clamp(18px, 2.25vw, 24px);
  font-weight: 500;
  line-height: 1.65;
}

.value-proposition__details {
  position: relative;
  display: grid;
  max-width: 760px;
  margin-top: 28px;
  color: var(--text-secondary);
  font-size: 16px;
  line-height: 1.8;
  gap: 8px;
}

.value-proposition__details p {
  margin: 0;
}

@media (max-width: 520px) {
  .value-proposition {
    padding: 0 18px;
  }

  .value-proposition__card {
    padding: 30px 24px;
  }

  blockquote {
    margin-top: 26px;
    padding-left: 16px;
  }

  .value-proposition__details {
    margin-top: 22px;
    font-size: 15px;
    line-height: 1.75;
  }
}
</style>
