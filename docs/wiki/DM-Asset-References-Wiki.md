# Where Is This Image Used? (Dynamic Media Asset References)

## In one sentence

When an author puts a Dynamic Media image on a website page, AEM Assets learns about it within about a minute, even if the page is not published yet. Anyone looking at the image can then see every page that uses it, and whether each page is live or still a draft.

## The problem today

Images live in **AEM Assets**. Websites are built in **AEM Sites**, which is often in the customer's own data center behind their firewall.

When someone in Assets asks "Can I delete or change this image? Where is it used?", the answer today is **wrong or missing**. It depends on website traffic logs that are collected by a slow overnight process, and those logs often can't see inside the customer's network.

## How the new approach works

![Architecture diagram](dm-references-architecture.png)

1. **The author edits a page.** They add, swap or remove an image. Publishing or unpublishing the page counts too.
2. **The Reference Tracker notices.** This small add-on on the Sites side waits until the author has stopped editing for a minute, then reads the page as it is now and records which images it uses, in the draft and on the live site. It reads the page once for a whole editing session, not once per save, and does nothing if the images didn't change. That includes images that come from the page's template, such as a logo or header.
3. **It sends a short update.** It sends Assets a note such as "this page now uses these images." The connection only goes **out** from the customer's network, so their firewall rules don't need to change.
4. **The Reference Receiver records it.** On the Assets side, it checks the sender is allowed and updates the index of which pages use which images. The draft list and the live list are kept apart, so editing a draft never changes what is shown as live.
5. **Anyone can look it up.** Open an image and you see the pages using it.

Pages that were already live before the tracker was installed are loaded once with a **catch-up run** that an administrator starts by hand.

Think of it as a library card system. Every time a book (image) is placed on a shelf (page), a card is filed. When the book moves, the card is updated. Ask about any book and the cards tell you where it is.

## Examples

| What the author does | What Assets shows (within about a minute) |
|---|---|
| Maria adds `summer-hero.jpg` to the *Summer Sale* page, not published yet | `summer-hero.jpg` → *Summer Sale* page, **draft** (within about a minute) |
| Maria publishes the page | The same row now says **draft and live** |
| Maria edits the text only | Nothing changes, and nothing is sent |
| Maria replaces the image with `autumn-hero.jpg` and publishes | `summer-hero.jpg` no longer lists the page. `autumn-hero.jpg` does |
| Maria removes an image from the draft but doesn't publish yet | The row says **live only**, because the live page hasn't changed |
| The page is unpublished | The row stays, marked **draft only** |
| The page is deleted | The page disappears from the image's list |
| The page is moved | The old address disappears. The new address appears once it is edited or published |
| The logo in the page template is a Dynamic Media image | Every page that uses the template is listed for that logo, once it is published or edited |
| The page uses a friendly image name (a "vanity ID") like `summer-hero` instead of the image's internal ID | Assets matches the name to the right image and shows it the same way |

What a user sees when they look up an image:

```
summer-hero.jpg
  • /us/en/summer-sale      live + draft    (site: acme-prod)
  • /uk/en/summer-sale      draft only      (site: acme-prod)
```

## Why this is better

| | Before | Now |
|---|---|---|
| **Speed** | Hours to days | About a minute after an edit, seconds after publishing |
| **Works behind a customer firewall** | Often not | Yes: outgoing only |
| **Shows unpublished drafts** | No | Yes |
| **Needs extra provisioning per customer** | Yes (log storage) | No |

## Frequently asked questions

**Does this slow down authors?**
No. The tracker works in the background after the author saves.

**Is customer data safe?**
Only the page address and the image identifiers are sent. The connection is encrypted and uses a secure access key. Only an approved account can send updates, and people only see references for images they're allowed to open.

**What if the connection is down?**
Updates wait and are re-sent automatically, for up to about a day. Nothing is lost.

**Does it change the images themselves?**
No. References are stored separately, so the images are never touched.

**Does it replace the traffic-log reports?**
No. They work together: this approach shows where images are *placed*, and traffic logs show where they're *viewed*, including sites outside AEM.

## Current limits (MVP)

- Draft tracking is **best effort**. If the system restarts while an author's latest edits are still waiting, those edits are picked up at the next edit of the page.
- If an image sits inside a shared block (an **Experience Fragment**) or a page template, and that block or template is edited, pages keep showing the old images until they are edited or published again, or the catch-up run is repeated.
- "Live" means the page was *sent* to the live site. If publishing is stuck, it can show live a little early.
- A short wait (10 seconds by default) happens between publishing and the update. If it is set much longer, a newly published image can look unused for that long.
- For now the results come from a lookup address. They don't yet appear in the Assets screens.

## Glossary

| Term | Meaning |
|---|---|
| **AEM Sites** | Where web pages are built and published |
| **AEM Assets / Dynamic Media** | Where images and videos are stored and delivered |
| **Author / Publish** | Author is the editing workspace. Publish is the live website |
| **Reference** | A record that "page X uses image Y" |
| **Vanity ID** | A friendly name for an image, such as `summer-hero` |
| **On-prem / AMS** | AEM running in the customer's data center, or hosted by Adobe Managed Services |

## For technical readers

Code, setup steps and the API details are in the project [README](../../README.md).

- **Sites side:** a publish listener and a page-edit listener, with delayed background jobs. It keeps no local record of its own.
- **Assets side:** two endpoints:
  - `POST /bin/dmrefs/ingest` receives the updates
  - `GET /bin/dmrefs/references?path=/content/dam/...` looks them up
