import { loader } from '@monaco-editor/react';

const baseUrl = import.meta.env.BASE_URL.endsWith('/') ? import.meta.env.BASE_URL : `${import.meta.env.BASE_URL}/`;
loader.config({ paths: { vs: `${baseUrl}monaco/vs` } });
