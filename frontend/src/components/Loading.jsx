export default function Loading({ label = '加载中...' }) { return <div className="loading" role="status"><span className="spinner" />{label}</div>; }
