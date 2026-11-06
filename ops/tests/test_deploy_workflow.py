#!/usr/bin/env python3
"""Check deployment permissions and production secret boundaries."""
from pathlib import Path
import re

import yaml


def main() -> None:
    """Reject trigger, privilege, identity, or serialization regressions."""
    # Given the workflow consumed by GitHub Actions.
    source = Path('.github/workflows/deploy.yml').read_text(encoding='utf-8')
    workflow = yaml.load(source, Loader=yaml.BaseLoader)
    # When evaluating the job boundaries.
    assert workflow['on'] == {'push': {'branches': ['develop']}}, 'DEVELOP_ONLY'
    assert workflow['permissions'] == {'contents': 'read'}, 'DEFAULT_READ_ONLY'
    jobs = workflow['jobs']
    assert set(jobs) == {'build', 'publish', 'deploy'}, 'SEPARATE_JOBS'
    assert jobs['publish']['needs'] == 'build', 'BUILD_GATE'
    assert jobs['deploy']['needs'] == 'publish', 'PUBLICATION_GATE'
    assert jobs['publish']['permissions'] == {'contents': 'read', 'packages': 'write'}
    assert jobs['deploy']['permissions'] == {'contents': 'read'}
    assert jobs['deploy']['environment'] == 'production'
    assert jobs['deploy']['concurrency'] == {
        'group': 'danditon-production', 'cancel-in-progress': 'false',
    }
    deploy_step = next(
        step for step in jobs['deploy']['steps']
        if step.get('name') == 'Transfer and deploy verified release'
    )
    assert deploy_step['env']['JWT_SECRET_BASE64'] == '${{ secrets.JWT_SECRET_BASE64 }}', \
        'JWT_PRODUCTION_SECRET_REQUIRED'
    scripts = Path('ops/ci/deploy.sh').read_text(encoding='utf-8') + \
        Path('ops/deploy/deploy.sh').read_text(encoding='utf-8')
    assert 'jwt.env' not in scripts, 'NO_PER_RELEASE_JWT_FILE'
    # Then only the production job receives production secrets.
    for name, job in jobs.items():
        assert 1 <= int(job['timeout-minutes']) <= 20, 'BOUNDED_JOB'
        if name != 'deploy':
            assert 'secrets.' not in str(job), 'SECRET_ISOLATION'
            assert 'environment' not in job, 'ENVIRONMENT_ISOLATION'
        for step in job['steps']:
            if 'uses' in step:
                assert re.fullmatch(r'(actions|gradle)/[a-z/-]+@[0-9a-f]{40}', step['uses'])
            if step.get('uses', '').startswith('actions/checkout@'):
                assert step['with'] == {
                    'ref': '${{ github.sha }}', 'persist-credentials': 'false',
                }, 'EXACT_SHA'
    assert any(step.get('run') == './gradlew --no-daemon clean build'
               for step in jobs['build']['steps']), 'BUILD_TEST'
    print('PASS: develop-only deployment, job permissions, secret isolation, SHA, pins, serialization')


if __name__ == '__main__':
    main()
