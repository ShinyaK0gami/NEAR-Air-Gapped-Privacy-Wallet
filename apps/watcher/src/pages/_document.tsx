import { Html, Head, Main, NextScript } from "next/document";

export default function Document() {
  return (
    <Html lang="en" className="dark">
      <Head>
        <meta
          name="description"
          content="NearAir (AirNEAR) — Open-source, zero-cost, air-gapped cold storage signing system for NEAR Protocol"
        />
      </Head>
      <body className="bg-[#090d16] text-slate-100 antialiased">
        <Main />
        <NextScript />
      </body>
    </Html>
  );
}

