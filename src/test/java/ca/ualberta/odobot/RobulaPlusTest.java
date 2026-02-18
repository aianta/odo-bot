package ca.ualberta.odobot;

import ca.ualberta.odobot.common.RobulaPlus;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.NodeVisitor;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RobulaPlusTest {

    private static final Logger log = LoggerFactory.getLogger(RobulaPlusTest.class);

    private class RobulaVisitor implements NodeVisitor {

        RobulaPlus robbulaPlus = new RobulaPlus();
        Document document;

        public RobulaVisitor(Document document) {
            this.document = document;
        }

        @Override
        public void head(Node node, int i) {
            if (node instanceof Element){
                Element element = (Element) node;
                try{

                    String robustXpath = robbulaPlus.getRobustXPath(element, this.document);
                    log.info("robustXpath: {}", robustXpath);

                    assertEquals(document.selectXpath(robustXpath).get(0), element);

                }catch (IllegalStateException e){
                    log.error("Couldn't find robust xpath for element: {}", element.tag());
                }

            }
        }
    }


    @Test
    void testRobulaPlus() {

        Document document = Jsoup.parse(sampleHTML);

        document.traverse(new RobulaVisitor(document));

    }


    private static final String sampleHTML = """
            <html>
                    <head></head>
                    <body>
                     <title>Topic: Extremophiles and terrestrial analogs for space</title>
                     <div role="alert">
                      <div>
                       <i></i>
                      </div>
                      <h1>You need to have JavaScript enabled in order to access this site.</h1>
                     </div>
                     <div id="flash_message_holder"></div>
                     <div id="flash_screenreader_holder" role="alert">
                      <div id="Alert___1">
                       <span>The replies were successfully updated</span>
                      </div>
                     </div>
                     <div id="drawer-layout-mount-point">
                      <span>
                       <div>
                        <div id="drawer-layout-content" role="region">
                         <div>
                          <div id="application">
                           <button type="button"><i></i><span id="mobileHeaderInboxUnreadBadge"></span><span>Global Navigation Menu</span></button>
                           <div></div><a role="button">
                            <div>
                             AST312
                            </div>
                            <div>
                             Extremophiles and terrestrial analogs for space
                            </div></a>
                           <div></div>
                           <div id="mobile-top-nav-tools-mount-point">
                            <span>
                             <button id="Menu__label___0" type="button"><span><span><span><span>
                                  <svg name="IconLti" role="presentation"></svg></span><span>LTI Tool Menu</span></span></span></span></button></span>
                           </div>
                           <button type="button"><i id="mobileHeaderArrowIcon"></i></button>
                           <div></div><a id="skip_navigation_link">Skip To Content</a>
                           <div role="region">
                            <div>
                             <a href="http://localhost:8088/"><span>Dashboard</span></a>
                            </div>
                            <ul id="menu">
                             <li><a id="global_nav_profile_link" role="button">
                               <div>
                                <div>
                                 <img src="http://canvas.instructure.com/images/messages/avatar-50.png" alt="Riley Parker">
                                </div><span></span>
                               </div>
                               <div>
                                Account
                               </div></a></li>
                             <li><a id="global_nav_dashboard_link" href="http://localhost:8088/">
                               <div>
                                <svg></svg>
                               </div>
                               <div>
                                Dashboard
                               </div></a></li>
                             <li><a id="global_nav_courses_link" role="button">
                               <div>
                                <svg></svg>
                               </div>
                               <div>
                                Courses
                               </div></a></li>
                             <li><a id="global_nav_groups_link" role="button">
                               <div>
                                <svg></svg>
                               </div>
                               <div>
                                Groups
                               </div></a></li>
                             <li><a id="global_nav_calendar_link">
                               <div>
                                <svg></svg>
                               </div>
                               <div>
                                Calendar
                               </div></a></li>
                             <li><a id="global_nav_conversations_link">
                               <div>
                                <span>
                                 <svg></svg></span><span></span>
                               </div>
                               <div>
                                Inbox
                               </div></a></li>
                             <li><a id="global_nav_help_link" role="button">
                               <div role="presentation">
                                <svg></svg><span></span>
                               </div>
                               <div>
                                Help
                               </div></a></li>
                            </ul>
                           </div>
                           <div>
                            <ul>
                             <li><a id="primaryNavToggle" role="button" title="Minimize global navigation">
                               <div>
                                <svg></svg>
                               </div></a></li>
                            </ul>
                           </div>
                           <div id="global_nav_tray_container"></div>
                           <div id="global_nav_tour"></div>
                           <div id="instructure_ajax_error_box">
                            <div>
                             <a>Close</a>
                            </div><iframe id="instructure_ajax_error_result" title="Error"></iframe>
                           </div>
                           <div id="wrapper">
                            <div>
                             <button type="button" id="courseMenuToggle"><i></i></button>
                             <div>
                              <ul>
                               <li><a><span><i title="My Dashboard"><span>My Dashboard</span></i></span></a></li>
                               <li><a><span>AST312</span></a></li>
                               <li><a><span>Discussions</span></a></li>
                               <li><a><span>Extremophiles and terrestrial analogs for space</span></a></li>
                              </ul>
                             </div>
                             <div>
                              <div id="top-nav-tools-mount-point">
                               <div></div>
                              </div>
                             </div>
                            </div>
                            <div id="main">
                             <div></div>
                             <div id="left-side">
                              <div id="sticky-container">
                               <ul id="section-tabs">
                                <li><a id="home-link">Home</a></li>
                                <li><a id="announcements-link">Announcements</a></li>
                                <li><a id="assignments-link">Assignments</a></li>
                                <li><a id="discussions-link">Discussions</a></li>
                                <li><a id="grades-link">Grades<b>19</b></a></li>
                                <li><a id="people-link">People</a></li>
                                <li><a id="pages-link">Pages</a></li>
                                <li><a id="syllabus-link">Syllabus</a></li>
                                <li><a id="outcomes-link">Outcomes</a></li>
                                <li><a id="quizzes-link">Quizzes</a></li>
                                <li><a id="modules-link">Modules</a></li>
                               </ul>
                              </div>
                             </div>
                             <div id="not_right_side">
                              <div id="content-wrapper">
                               <div id="content" role="main">
                                <span>
                                 <div id="keyboard-shortcut-modal"></div></span><span>
                                 <div id="discussion-redesign-layout">
                                  <div>
                                   <div></div>
                                   <div>
                                    <div>
                                     <div>
                                      <div id="discussion-drawer-layout" role="region">
                                       <span id="module_sequence_footer_container">
                                        <div>
                                         <span>
                                          <h1>Extremophiles and terrestrial analogs for space</h1></span>
                                         <div>
                                          <span><span><span><span><span>
                                               <button type="button"><span><span><span><span>
                                                 <svg name="IconAdd" role="presentation"></svg></span></span><span><span>View Split Screen</span></span></span></span></button></span><span>
                                               <button type="button"><span><span><span><span>
                                                 <svg name="IconExpand" role="presentation"></svg></span></span><span><span>Expand Threads</span></span></span></span></button></span></span></span><span><span><span><span><span><label>
                                                 <div id="FormField-Label___0">
                                                 <span>Filter by</span>
                                                 </div><span><span><span><span><input id="viewSelect" role="combobox" title="All" type="text" value="All"><span><span>
                                                 <svg name="IconArrowOpenDown" role="presentation"></svg></span></span></span></span></span></span></label><span id="Selectable___0-description"></span><span><span></span></span></span></span></span><span><span><label>
                                                 <div id="FormField-Label___1">
                                                 <span>Search entries or author...</span>
                                                 </div><span><span><span>
                                                 <svg name="IconSearch" role="presentation"></svg><span><input placeholder="Search entries or author..." type="text" id="TextInput___1" value=""></span></span></span></span></label></span></span><span><span><span><label>
                                                 <div id="FormField-Label___2">
                                                 <span>Sort by</span>
                                                 </div><span><span><span><span><input id="Select___1" role="combobox" title="Newest First" type="text" value="Newest First"><span><span>
                                                 <svg name="IconArrowOpenDown" role="presentation"></svg></span></span></span></span></span></span></label><span id="Selectable___1-description"></span><span><span></span></span></span></span></span></span></span></span></span>
                                         </div>
                                        </div>
                                        <div>
                                         <div></div>
                                         <div>
                                          <div>
                                           <span>
                                            <div>
                                             <span><span>
                                               <div>
                                                <span><span>
                                                 <div>
                                                 <span><span></span><span>
                                                 <div></div></span></span>
                                                 </div></span></span>
                                               </div></span><span>
                                               <div>
                                                <span><span><span><span>
                                                 <div>
                                                 <span><span>
                                                 <div>
                                                 <span name="Dr Javier Santiago"><img><span>DS</span></span>
                                                 </div></span><span><span><span><span><span>
                                                 <div>
                                                 <a href="http://localhost:8088/courses/1/users/12"><span>Dr Javier Santiago</span></a>
                                                 </div></span><span>
                                                 <div>
                                                 <ul>
                                                 <li><span>Author</span><span></span></li>
                                                 <li><span>Teacher</span></li>
                                                 </ul>
                                                 </div></span></span></span><span><span><span><span>Posted Jan 16 12:05pm</span></span></span></span></span></span></span>
                                                 </div></span><span>
                                                 <div>
                                                 <span><span><span>
                                                 <div>
                                                 <span><span>1 Reply</span><span>
                                                 <div>
                                                 1 Reply
                                                 </div></span></span>
                                                 </div></span></span><span><span><span><span><span>
                                                 <button type="button"><span><span><span><span>
                                                 <svg name="IconBookmark" role="presentation"></svg></span><span>Unsubscribed</span></span></span></span></button></span></span></span><span><span><span id="Menu__label___2">
                                                 <button type="button"><span><span><span><span>
                                                 <svg name="IconMore" role="presentation"></svg></span><span>Manage Discussion</span></span></span></span></button></span></span></span></span></span></span>
                                                 </div></span></span></span><span>
                                                 <div>
                                                 <span><span>
                                                 <h2><span><span>Discussion Topic: Extremophiles and terrestrial analogs for space</span><span>Extremophiles and terrestrial analogs for space</span></span></h2></span>
                                                 <div>
                                                 <span>As we explore extremophiles and terrestrial analogs for space this week I want you to reflect on examples from Earth that could inform life detection on other worlds Share a brief description of a specific analog environment or organism and explain why it is relevant to astrobiology You can mention field trips lab work literature or media that shaped your view</span>
                                                 </div><span><span><span><span>
                                                 <button type="button"><span><span><span>Reply</span></span></span></button></span></span><span><span></span></span></span></span></span>
                                                 </div></span></span>
                                               </div></span><span></span></span>
                                            </div></span>
                                          </div>
                                         </div>
                                        </div>
                                        <div>
                                         <div>
                                          <div>
                                           <div>
                                            <span><span>
                                              <div>
                                               <span><span><span><span>
                                                 <div>
                                                 <span><span>
                                                 <div>
                                                 <span name="Sarah Studentson"><img><span>SS</span></span>
                                                 </div></span><span><span><span><span><span>
                                                 <div>
                                                 <a href="http://localhost:8088/courses/1/users/4"><span>Sarah Studentson</span></a>
                                                 </div></span><span>
                                                 <div></div></span></span></span><span><span><span><span>Jan 16 12:05pm</span></span></span></span></span></span></span>
                                                 </div></span><span><span>
                                                 <button id="Menu__label___1" type="button"><span><span><span><span>
                                                 <svg name="IconMore" role="presentation"></svg></span><span>Manage Discussion by Sarah Studentson</span></span></span></span></button></span></span></span></span><span>
                                                 <div>
                                                 <span>
                                                 <h3><span><span><span>Reply from Sarah Studentson</span><span></span></span></span></h3>
                                                 <div>
                                                 <span>The hypersaline springs I read about are a great example Their salt tolerant microbes demonstrate extreme osmotic adaptation and metabolic flexibility Studying them could help us predict where life might persist on ancient Mars or salty ocean worlds</span>
                                                 </div><span>
                                                 <div>
                                                 <div>
                                                 <span>
                                                 <ul>
                                                 <li><span>
                                                 <div>
                                                 <span>
                                                 <button type="button"><span>
                                                 <svg name="IconDiscussionReply2" role="presentation"></svg></span><span><span>Reply to post from Sarah Studentson</span><span><span>Reply</span></span></span></button></span>
                                                 </div></span><span></span></li>
                                                 <li><span>
                                                 <div>
                                                 <span>
                                                 <button type="button"><span><span>
                                                 <svg name="IconLike" role="presentation"></svg><span>Like post from Sarah Studentson</span>Like</span></span><span>Like count: 0</span></button></span>
                                                 </div></span><span></span></li>
                                                 <li><span>
                                                 <div>
                                                 <span>
                                                 <button type="button"><span>
                                                 <svg role="img">
                                                 <title id="InlineSVG-title___21">
                                                 unread
                                                 </title>
                                                 </svg></span><span><span>Mark as Unread</span><span><span>Mark as Unread</span></span></span></button></span>
                                                 </div></span></li>
                                                 </ul></span>
                                                 </div>
                                                 </div></span></span>
                                                 </div></span></span>
                                              </div></span></span>
                                           </div>
                                          </div>
                                         </div>
                                        </div></span>
                                      </div>
                                     </div>
                                    </div>
                                   </div>
                                  </div>
                                 </div></span>
                               </div>
                              </div>
                              <div id="right-side-wrapper"></div>
                             </div>
                            </div>
                           </div>
                           <div></div>
                           <div id="aria_alerts" role="alert"></div>
                           <div id="StudentTray__Container"></div>
                           <div id="react-router-portals"></div><iframe name="post_message_forwarding" title="post_message_forwarding" id="post_message_forwarding"></iframe>
                          </div>
                         </div>
                        </div>
                       </div></span>
                     </div>
                     <div id="nav-tray-portal"></div>
                     <div></div><span><span><span><span></span><span id="Tooltip___0" role="tooltip">Subscribe</span></span></span></span><span><span><span role="dialog">
                        <div>
                         <div>
                          <span>
                           <button type="button"><span><span><span><span>
                                <svg name="IconX" role="presentation"></svg></span><span>Close</span></span></span></span></button></span>
                          <h2>Report Reply</h2>
                         </div>
                         <div>
                          <div>
                           <span>Reported replies will be sent to your teacher for review. You will not be able to undo this action.</span>
                          </div>
                          <fieldset role="radiogroup">
                           <legend><span>Please select a reason for reporting this reply</span></legend><span><span><span><span><span>
                                <div>
                                 <div>
                                  <input id="RadioInput___0" name="Report Reply Options" type="radio" value="inappropriate"><label><span></span><span>Inappropriate</span></label>
                                 </div>
                                </div></span><span>
                                <div>
                                 <div>
                                  <input id="RadioInput___1" name="Report Reply Options" type="radio" value="offensive"><label><span></span><span>Offensive, abusive</span></label>
                                 </div>
                                </div></span><span>
                                <div>
                                 <div>
                                  <input id="RadioInput___2" name="Report Reply Options" type="radio" value="other"><label><span></span><span>Other</span></label>
                                 </div>
                                </div></span></span></span></span></span>
                          </fieldset>
                         </div>
                        </div>
                        <div>
                         <span>
                          <button type="button"><span><span>Cancel</span></span></button></span><span>
                          <button type="button"><span><span>Submit</span></span></button></span>
                        </div></span></span></span>
                    </body>
                   </html>
            """;

}
