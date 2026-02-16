import React, {useEffect, useState} from 'react';
import useBaseUrl from '@docusaurus/useBaseUrl';

interface VersionEntry {
  version: string;
  date: string;
  reportPath: string;
}

interface Manifest {
  versions: VersionEntry[];
}

export default function AutobahnVersions(): React.JSX.Element {
  const [manifest, setManifest] = useState<Manifest | null>(null);
  const manifestUrl = useBaseUrl('/autobahn/manifest.json');

  useEffect(() => {
    fetch(manifestUrl)
      .then(res => res.json())
      .then(data => setManifest(data))
      .catch(() => setManifest({versions: []}));
  }, [manifestUrl]);

  if (!manifest) {
    return <p>Loading report versions...</p>;
  }

  if (manifest.versions.length === 0) {
    return (
      <div style={{padding: '20px', background: 'var(--ifm-color-emphasis-100)', borderRadius: '8px'}}>
        <p>No reports published yet. Reports are generated automatically on release and available as CI artifacts on pull requests.</p>
      </div>
    );
  }

  return (
    <table>
      <thead>
        <tr>
          <th>Version</th>
          <th>Date</th>
          <th>Report</th>
        </tr>
      </thead>
      <tbody>
        {manifest.versions.map((entry) => (
          <tr key={entry.version}>
            <td><strong>{entry.version}</strong></td>
            <td>{entry.date}</td>
            <td>
              <a href={useBaseUrl(`/autobahn/${entry.reportPath}`)}>
                View Report →
              </a>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
