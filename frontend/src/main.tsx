import { createRoot } from 'react-dom/client';
import { AuthGate } from './components/AuthGate';
import 'antd/dist/reset.css';
import './styles.css';

const container = document.getElementById('root')!;
// index.html paints a shell placeholder so the page is not blank while this
// bundle loads. Drop it explicitly rather than relying on createRoot's own
// container clearing, which is an implementation detail.
container.replaceChildren();
createRoot(container).render(<AuthGate />);
