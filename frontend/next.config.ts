import type { NextConfig } from 'next';

const backend = process.env.IDENTITY_API_URL || 'http://localhost:8080';
if (!/^https?:\/\//.test(backend)) throw new Error('IDENTITY_API_URL must be an HTTP(S) URL');

const config: NextConfig = {
  output: process.env.NEXT_STANDALONE === '1' ? 'standalone' : undefined,
  async rewrites() {
    return [{ source: '/api/:path*', destination: `${backend.replace(/\/$/, '')}/api/:path*` }];
  },
};

export default config;
